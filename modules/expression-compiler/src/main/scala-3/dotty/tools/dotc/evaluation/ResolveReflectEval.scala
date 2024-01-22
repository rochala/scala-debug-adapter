package dotty.tools.dotc.evaluation

import dotty.tools.dotc.ExpressionContext
import dotty.tools.dotc.ast.tpd.*
import dotty.tools.dotc.core.Constants.Constant
import dotty.tools.dotc.core.Contexts.*
import dotty.tools.dotc.core.Flags.*
import dotty.tools.dotc.core.Names.*
import dotty.tools.dotc.core.Symbols.*
import dotty.tools.dotc.core.Types.*
import dotty.tools.dotc.transform.MegaPhase.MiniPhase
import dotty.tools.dotc.core.StdNames.*
import dotty.tools.dotc.core.NameKinds.QualifiedInfo
import dotty.tools.dotc.evaluation.SymUtils.*
import dotty.tools.dotc.report
import dotty.tools.dotc.core.Phases
import dotty.tools.dotc.core.TypeErasure.ErasedValueType
import dotty.tools.dotc.transform.ValueClasses

class ResolveReflectEval(using exprCtx: ExpressionContext) extends MiniPhase:
  override def phaseName: String = ResolveReflectEval.name

  override def transformTypeDef(tree: TypeDef)(using Context): Tree =
    ExpressionTransformer.transform(tree)

  object ExpressionTransformer extends TreeMap:
    override def transform(tree: Tree)(using Context): Tree =
      tree match
        case tree: DefDef if tree.symbol == exprCtx.evaluateMethod =>
          // unbox the result of the `evaluate` method if it is a value class
          val gen = new Gen(
            Apply(
              Select(This(exprCtx.expressionClass), termName("reflectEval")),
              List(nullLiteral, nullLiteral, nullLiteral)
            )
          )
          val rhs = gen.unboxIfValueClass(exprCtx.expressionSymbol, transform(tree.rhs))
          cpy.DefDef(tree)(rhs = rhs)

        case reflectEval: Apply if isReflectEval(reflectEval.fun.symbol) =>
          val qualifier :: _ :: argsTree :: Nil = reflectEval.args.map(transform): @unchecked
          val args = argsTree.asInstanceOf[JavaSeqLiteral].elems
          val gen = new Gen(reflectEval)
          tree.attachment(EvaluationStrategy) match
            case EvaluationStrategy.This(cls) =>
              if cls.isValueClass then
                // if cls is a value class then the local $this is the erased value,
                // but we expect an instance of the value class instead
                gen.boxValueClass(cls, gen.getLocalValue("$this"))
              else gen.getLocalValue("$this")
            case EvaluationStrategy.LocalOuter(cls) =>
              gen.getLocalValue("$outer")
            case EvaluationStrategy.Outer(outerCls) =>
              gen.getOuter(qualifier, outerCls)
            case EvaluationStrategy.LocalValue(variable, isByName) =>
              val variableName = JavaEncoding.encode(variable.name)
              val rawLocalValue = gen.getLocalValue(variableName)
              val localValue = if isByName then gen.evaluateByName(rawLocalValue) else rawLocalValue
              val derefLocalValue = gen.derefCapturedVar(localValue, variable)
              gen.boxIfValueClass(variable, derefLocalValue)
            case EvaluationStrategy.LocalValueAssign(variable) =>
              val value = gen.unboxIfValueClass(variable, args.head)
              val typeSymbol = variable.info.typeSymbol.asType
              val variableName = JavaEncoding.encode(variable.name)
              JavaEncoding.encode(typeSymbol) match
                case s"scala.runtime.${_}Ref" =>
                  val elemField = typeSymbol.info.decl(termName("elem")).symbol
                  gen.setField(
                    gen.getLocalValue(variableName),
                    elemField.asTerm,
                    value
                  )
                case _ => gen.setLocalValue(variableName, value)
            case EvaluationStrategy.ClassCapture(variable, cls, isByName) =>
              val rawCapture = gen
                .getClassCapture(qualifier, variable.name, cls)
                .getOrElse {
                  report.error(s"No capture found for $variable in $cls", tree.srcPos)
                  ref(defn.Predef_undefined)
                }
              val capture = if isByName then gen.evaluateByName(rawCapture) else rawCapture
              val capturedValue = gen.derefCapturedVar(capture, variable)
              gen.boxIfValueClass(variable, capturedValue)
            case EvaluationStrategy.ClassCaptureAssign(variable, cls) =>
              val capture = gen
                .getClassCapture(qualifier, variable.name, cls)
                .getOrElse {
                  report.error(s"No capture found for $variable in $cls", tree.srcPos)
                  ref(defn.Predef_undefined)
                }
              val value = gen.unboxIfValueClass(variable, args.head)
              val typeSymbol = variable.info.typeSymbol
              val elemField = typeSymbol.info.decl(termName("elem")).symbol
              gen.setField(capture, elemField.asTerm, value)
            case EvaluationStrategy.MethodCapture(variable, method, isByName) =>
              val rawCapture = gen
                .getMethodCapture(method, variable.name)
                .getOrElse {
                  report.error(s"No capture found for $variable in $method", tree.srcPos)
                  ref(defn.Predef_undefined)
                }
              val capture = if isByName then gen.evaluateByName(rawCapture) else rawCapture
              val capturedValue = gen.derefCapturedVar(capture, variable)
              gen.boxIfValueClass(variable, capturedValue)
            case EvaluationStrategy.MethodCaptureAssign(variable, method) =>
              val capture = gen
                .getMethodCapture(method, variable.name)
                .getOrElse {
                  report.error(s"No capture found for $variable in $method", tree.srcPos)
                  ref(defn.Predef_undefined)
                }
              val value = gen.unboxIfValueClass(variable, args.head)
              val typeSymbol = variable.info.typeSymbol
              val elemField = typeSymbol.info.decl(termName("elem")).symbol
              gen.setField(capture, elemField.asTerm, value)
            case EvaluationStrategy.StaticObject(obj) =>
              gen.getStaticObject(obj)
            case EvaluationStrategy.Field(field, isByName) =>
              // if the field is lazy, if it is private in a value class or a trait
              // then we must call the getter method
              val fieldValue =
                if field.is(Lazy) || field.owner.isValueClass || field.owner.is(Trait)
                then gen.callMethod(qualifier, field.getter.asTerm, Nil)
                else
                  val rawValue = gen.getField(qualifier, field)
                  if isByName then gen.evaluateByName(rawValue) else rawValue
              gen.boxIfValueClass(field, fieldValue)
            case EvaluationStrategy.FieldAssign(field) =>
              val arg = gen.unboxIfValueClass(field, args.head)
              if field.owner.is(Trait) then gen.callMethod(qualifier, field.setter.asTerm, List(arg))
              else gen.setField(qualifier, field, arg)
            case EvaluationStrategy.MethodCall(method) =>
              gen.callMethod(qualifier, method, args)
            case EvaluationStrategy.ConstructorCall(ctr, cls) =>
              gen.callConstructor(qualifier, ctr, args)
        case _ => super.transform(tree)

  private def isReflectEval(symbol: Symbol)(using Context): Boolean =
    symbol.name == termName("reflectEval") &&
      symbol.owner == exprCtx.expressionClass

  class Gen(reflectEval: Apply)(using Context):
    private val expressionThis = reflectEval.fun.asInstanceOf[Select].qualifier

    def derefCapturedVar(tree: Tree, term: TermSymbol): Tree =
      val typeSymbol = term.info.typeSymbol.asType
      JavaEncoding.encode(typeSymbol) match
        case s"scala.runtime.${_}Ref" =>
          val elemField = typeSymbol.info.decl(termName("elem")).symbol
          getField(tree, elemField.asTerm)
        case _ => tree

    def boxIfValueClass(term: TermSymbol, tree: Tree): Tree =
      getErasedValueType(atPhase(Phases.elimErasedValueTypePhase)(term.info)) match
        case Some(erasedValueType) =>
          boxValueClass(erasedValueType.tycon.typeSymbol.asClass, tree)
        case None => tree

    def boxValueClass(valueClass: ClassSymbol, tree: Tree): Tree =
      // qualifier is null: a value class cannot be nested into a class
      val ctor = valueClass.primaryConstructor.asTerm
      callConstructor(nullLiteral, ctor, List(tree))

    def unboxIfValueClass(term: TermSymbol, tree: Tree): Tree =
      getErasedValueType(atPhase(Phases.elimErasedValueTypePhase)(term.info)) match
        case Some(erasedValueType) => unboxValueClass(tree, erasedValueType)
        case None => tree

    private def getErasedValueType(tpe: Type): Option[ErasedValueType] = tpe match
      case tpe: ErasedValueType => Some(tpe)
      case tpe: MethodOrPoly => getErasedValueType(tpe.resultType)
      case tpe => None

    private def unboxValueClass(tree: Tree, tpe: ErasedValueType): Tree =
      val cls = tpe.tycon.typeSymbol.asClass
      val unboxMethod = ValueClasses.valueClassUnbox(cls).asTerm
      callMethod(tree, unboxMethod, Nil)

    def getLocalValue(name: String): Tree =
      Apply(
        Select(expressionThis, termName("getLocalValue")),
        List(Literal(Constant(name)))
      )

    def setLocalValue(name: String, value: Tree): Tree =
      Apply(
        Select(expressionThis, termName("setLocalValue")),
        List(Literal(Constant(name)), value)
      )

    def getOuter(qualifier: Tree, outerCls: ClassSymbol): Tree =
      Apply(
        Select(expressionThis, termName("getOuter")),
        List(qualifier, Literal(Constant(JavaEncoding.encode(outerCls))))
      )

    def getClassCapture(
        qualifier: Tree,
        originalName: Name,
        cls: ClassSymbol
    ): Option[Tree] =
      cls.info.decls.iterator
        .filter(term => term.isField)
        .find { field =>
          field.name match
            case DerivedName(underlying, _) if field.isPrivate =>
              underlying == originalName
            case DerivedName(DerivedName(_, info: QualifiedInfo), _) =>
              info.name == originalName
            case _ => false
        }
        .map(field => getField(qualifier, field.asTerm))

    def getMethodCapture(
        method: TermSymbol,
        originalName: TermName
    ): Option[Tree] =
      val methodType = method.info.asInstanceOf[MethodType]
      methodType.paramNames
        .find {
          case DerivedName(n, _) => n == originalName
          case _ => false
        }
        .map { param =>
          val paramName = JavaEncoding.encode(param)
          getLocalValue(paramName)
        }

    def getStaticObject(obj: ClassSymbol): Tree =
      val className = JavaEncoding.encode(obj)
      Apply(
        Select(expressionThis, termName("getStaticObject")),
        List(Literal(Constant(className)))
      )

    def getField(qualifier: Tree, field: TermSymbol): Tree =
      Apply(
        Select(expressionThis, termName("getField")),
        List(
          qualifier,
          Literal(Constant(JavaEncoding.encode(field.owner.asType))),
          Literal(Constant(JavaEncoding.encode(field.name)))
        )
      )

    def setField(qualifier: Tree, field: TermSymbol, value: Tree): Tree =
      Apply(
        Select(expressionThis, termName("setField")),
        List(
          qualifier,
          Literal(Constant(JavaEncoding.encode(field.owner.asType))),
          Literal(Constant(JavaEncoding.encode(field.name))),
          value
        )
      )

    def evaluateByName(function: Tree): Tree =
      val castFunction = function.cast(defn.Function0.typeRef.appliedTo(defn.AnyType))
      Apply(Select(castFunction, termName("apply")), List())

    def callMethod(
        qualifier: Tree,
        method: TermSymbol,
        args: List[Tree]
    ): Tree =
      val methodType = method.info.asInstanceOf[MethodType]
      val paramTypesNames = methodType.paramInfos.map(JavaEncoding.encode)
      val paramTypesArray = JavaSeqLiteral(
        paramTypesNames.map(t => Literal(Constant(t))),
        TypeTree(ctx.definitions.StringType)
      )
      val capturedArgs =
        methodType.paramNames.dropRight(args.size).map {
          case name @ DerivedName(underlying, _) =>
            capturedValue(method, underlying)
              .getOrElse {
                report.error(s"Unknown captured variable $name in $method", reflectEval.srcPos)
                ref(defn.Predef_undefined)
              }
          case name =>
            report
              .error(
                s"Unknown captured variable $name in $method",
                reflectEval.srcPos
              )
            ref(defn.Predef_undefined)
        }

      val erasedMethodInfo =
        atPhase(Phases.elimErasedValueTypePhase)(method.info)
          .asInstanceOf[MethodType]
      val unboxedArgs =
        erasedMethodInfo.paramInfos.takeRight(args.size).zip(args).map {
          case (tpe: ErasedValueType, arg) => unboxValueClass(arg, tpe)
          case (_, arg) => arg
        }

      val returnTypeName = JavaEncoding.encode(methodType.resType)
      val methodName = JavaEncoding.encode(method.name)
      val result = Apply(
        Select(expressionThis, termName("callMethod")),
        List(
          qualifier,
          Literal(Constant(JavaEncoding.encode(method.owner.asType))),
          Literal(Constant(methodName)),
          paramTypesArray,
          Literal(Constant(returnTypeName)),
          JavaSeqLiteral(
            capturedArgs ++ unboxedArgs,
            TypeTree(ctx.definitions.ObjectType)
          )
        )
      )
      erasedMethodInfo.resType match
        case tpe: ErasedValueType =>
          boxValueClass(tpe.tycon.typeSymbol.asClass, result)
        case _ => result

    def callConstructor(
        qualifier: Tree,
        ctr: TermSymbol,
        args: List[Tree]
    ): Tree =
      val methodType = ctr.info.asInstanceOf[MethodType]
      val paramTypesNames = methodType.paramInfos.map(JavaEncoding.encode)
      val clsName = JavaEncoding.encode(methodType.resType)

      val capturedArgs =
        methodType.paramNames.dropRight(args.size).map {
          case outer if outer.toString == "$outer" => qualifier
          case name @ DerivedName(underlying, _) =>
            // if derived then probably a capture
            capturedValue(ctr.owner, underlying)
              .getOrElse {
                report.error(s"Unknown captured variable $name in $ctr of ${ctr.owner}", reflectEval.srcPos)
                ref(defn.Predef_undefined)
              }
          case name =>
            val paramName = JavaEncoding.encode(name)
            getLocalValue(paramName)
        }

      val erasedCtrInfo = atPhase(Phases.elimErasedValueTypePhase)(ctr.info)
        .asInstanceOf[MethodType]
      val unboxedArgs =
        erasedCtrInfo.paramInfos.takeRight(args.size).zip(args).map {
          case (tpe: ErasedValueType, arg) => unboxValueClass(arg, tpe)
          case (_, arg) => arg
        }

      val paramTypesArray = JavaSeqLiteral(
        paramTypesNames.map(t => Literal(Constant(t))),
        TypeTree(ctx.definitions.StringType)
      )
      Apply(
        Select(expressionThis, termName("callConstructor")),
        List(
          Literal(Constant(clsName)),
          paramTypesArray,
          JavaSeqLiteral(
            capturedArgs ++ unboxedArgs,
            TypeTree(ctx.definitions.ObjectType)
          )
        )
      )

    private def capturedValue(
        sym: Symbol,
        originalName: TermName
    ): Option[Tree] =
      val encodedName = JavaEncoding.encode(originalName)
      if exprCtx.classOwners.contains(sym)
      then capturedByClass(sym.asClass, originalName)
      else
      // if the captured value is not a local variables
      // then it must have been captured by the outer method
      if exprCtx.localVariables.contains(encodedName)
      then Some(getLocalValue(encodedName))
      else exprCtx.capturingMethod.flatMap(getMethodCapture(_, originalName))

    private def capturedByClass(
        cls: ClassSymbol,
        originalName: TermName
    ): Option[Tree] =
      val target = exprCtx.classOwners.indexOf(cls)
      val qualifier = exprCtx.classOwners
        .drop(1)
        .take(target)
        .foldLeft(getLocalValue("$this"))((q, cls) => getOuter(q, cls))
      getClassCapture(qualifier, originalName, cls)

object ResolveReflectEval:
  val name = "resolve-reflect-eval"
