package dotty.tools.dotc.evaluation

import dotty.tools.dotc.core.Symbols.*
import dotty.tools.dotc.util.Property.*

/**
 * The [[ExtractExpression]] phase attaches an [[EvaluationStrategy]] to each `reflectEval` node
 * to store information about the term that must be evaluated
 * Later, the [[ResolveReflectEval]] phase transforms each evaluation strategy into a call of
 * a method of the evaluation class.
 */
enum EvaluationStrategy:
  case This(cls: ClassSymbol)
  case Outer(outerCls: ClassSymbol)
  case LocalOuter(outerCls: ClassSymbol) // the $outer param in a constructor
  case LocalValue(variable: TermSymbol, isByName: Boolean)
  case LocalValueAssign(variable: TermSymbol)
  case MethodCapture(variable: TermSymbol, method: TermSymbol, isByName: Boolean)
  case MethodCaptureAssign(variable: TermSymbol, method: TermSymbol)
  case ClassCapture(variable: TermSymbol, cls: ClassSymbol, isByName: Boolean)
  case ClassCaptureAssign(variable: TermSymbol, cls: ClassSymbol)
  case StaticObject(obj: ClassSymbol)
  case Field(field: TermSymbol, isByName: Boolean)
  case FieldAssign(field: TermSymbol)
  case MethodCall(method: TermSymbol)
  case ConstructorCall(ctr: TermSymbol, cls: ClassSymbol)

object EvaluationStrategy extends StickyKey[EvaluationStrategy]
