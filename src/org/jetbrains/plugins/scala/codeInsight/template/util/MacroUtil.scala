package org.jetbrains.plugins.scala
package codeInsight.template.util

import com.intellij.codeInsight.template.{ExpressionContext, Result}
import com.intellij.openapi.project.Project
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi._
import com.intellij.util.IncorrectOperationException
import org.jetbrains.plugins.scala.lang.completion.lookups.ScalaLookupItem
import org.jetbrains.plugins.scala.lang.psi.api.expr.ScExpression
import org.jetbrains.plugins.scala.lang.psi.api.toplevel.typedef.{ScObject, ScTypeDefinition}
import org.jetbrains.plugins.scala.lang.psi.impl.ScalaPsiElementFactory
import org.jetbrains.plugins.scala.lang.psi.types.{ScParameterizedType, JavaArrayType, ScType}
import org.jetbrains.plugins.scala.lang.resolve.{ScalaResolveResult, StdKinds}

/**
 * User: Alexander Podkhalyuzin
 * Date: 30.01.2009
 */

object MacroUtil {

  /**
   * @param element from which position we look at locals
   * @return visible variables and values from element position
   */
  def getVariablesForScope(element: PsiElement): Array[ScalaResolveResult] = {
    val completionProcessor = new VariablesCompletionProcessor(StdKinds.valuesRef)
    PsiTreeUtil.treeWalkUp(completionProcessor, element, null, ResolveState.initial)
    completionProcessor.candidates
  }

  def resultToScExpr(result: Result, context: ExpressionContext): Option[ScExpression] =
    try {
      Option(PsiDocumentManager.getInstance(context.getProject).getPsiFile(context.getEditor.getDocument)).
              map(_.findElementAt(context.getStartOffset)).filter(_ != null).
              map(ScalaPsiElementFactory.createExpressionFromText(result.toString, _).asInstanceOf[ScExpression])
    } catch {
      case _: IncorrectOperationException => None
    }

  def getComponentFromArrayType(scType: ScType): Option[ScType] = scType match {
    case javaArrType: JavaArrayType => Some(javaArrType.arg)
    case paramType: ScParameterizedType if paramType.canonicalText.startsWith("_root_.scala.Array") &&
            paramType.typeArgs.length == 1 => Some(paramType.typeArgs.head)
    case _ => None
  }

  def getTypeLookupItem(scType: ScType, project: Project): Option[ScalaLookupItem] = {
    ScType.extractClass(scType, Some(project)).filter(_.isInstanceOf[ScTypeDefinition]).map{
      case typeDef: ScTypeDefinition =>
        val lookupItem = new ScalaLookupItem(typeDef, typeDef.getTruncedQualifiedName, Option(typeDef.getContainingClass))
        lookupItem.shouldImport = true
        lookupItem
    }
  }

  val scalaIdPrefix = "scala_"
  val scalaPresentablePrefix = "scala_"
}