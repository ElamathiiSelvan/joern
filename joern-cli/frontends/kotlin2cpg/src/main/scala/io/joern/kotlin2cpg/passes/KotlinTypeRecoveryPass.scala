package io.joern.kotlin2cpg.passes

import io.joern.kotlin2cpg.Constants
import io.joern.x2cpg.Defines
import io.joern.x2cpg.passes.frontend.*
import io.joern.x2cpg.passes.frontend.ImportsPass.ResolvedImport
import io.shiftleft.codepropertygraph.Cpg
import io.shiftleft.codepropertygraph.generated.PropertyNames
import io.shiftleft.codepropertygraph.generated.nodes.*
import overflowdb.BatchedUpdate.DiffGraphBuilder

import java.util.concurrent.ExecutorService
import overflowdb.traversal.ImplicitsTmp.toTraversalSugarExt
class KotlinTypeRecoveryPass(cpg: Cpg, config: TypeRecoveryConfig = TypeRecoveryConfig())
    extends XTypeRecoveryPass(cpg, config) {
  override protected def generateRecoveryPass(state: TypeRecoveryState, executor: ExecutorService): XTypeRecovery =
    new KotlinTypeRecovery(cpg, state, executor)
}

private class KotlinTypeRecovery(cpg: Cpg, state: TypeRecoveryState, executor: ExecutorService)
    extends XTypeRecovery(cpg, state, executor) {

  private def kotlinNodeToLocalKey(n: AstNode): Option[LocalKey] = n match {
    case i: Identifier if i.name == "this" && i.code == "super" => Option(LocalVar("super"))
    case _                                                      => SBKey.fromNodeToLocalKey(n)
  }

  override protected val initialSymbolTable = new SymbolTable[LocalKey](kotlinNodeToLocalKey)

  override protected def recoverTypesForProcedure(
    cpg: Cpg,
    procedure: Method,
    initialSymbolTable: SymbolTable[LocalKey],
    builder: DiffGraphBuilder,
    state: TypeRecoveryState
  ): RecoverTypesForProcedure = new RecoverForKotlinProcedure(cpg, procedure, initialSymbolTable, builder, state)

  override protected def importNodes(cu: File): List[ResolvedImport] =
    cu.namespaceBlock.flatMap(_.astOut).collectAll[Import].flatMap(visitImport).l

  // Kotlin has a much simpler import structure that doesn't need resolution
  override protected def visitImport(i: Import): Iterator[ImportsPass.ResolvedImport] = {
    for {
      alias    <- i.importedAs
      fullName <- i.importedEntity
    } {
      if (alias != Constants.wildcardImportName) {
        initialSymbolTable.append(CallAlias(alias, Option("this")), fullName)
        initialSymbolTable.append(LocalVar(alias), fullName)
      }
    }
    Iterator.empty
  }

  override protected def postVisitImports(): Unit = {
    initialSymbolTable.view.foreach { case (k, ts) =>
      val tss = ts.filterNot(_.startsWith(Defines.UnresolvedNamespace))
      if (tss.isEmpty)
        initialSymbolTable.remove(k)
      else
        initialSymbolTable.put(k, tss)
    }
  }

}

private class RecoverForKotlinProcedure(
  cpg: Cpg,
  procedure: Method,
  symbolTable: SymbolTable[LocalKey],
  builder: DiffGraphBuilder,
  state: TypeRecoveryState
) extends RecoverTypesForProcedure(cpg, procedure, symbolTable, builder, state) {

  override protected def isConstructor(c: Call): Boolean = isConstructor(c.name)

  override protected def isConstructor(name: String): Boolean = !name.isBlank && name.charAt(0).isUpper

  // There seems to be issues with inferring these, often due to situations where super and this are confused on name
  // and code properties.
  override protected def storeIdentifierTypeInfo(i: Identifier, types: Seq[String]): Unit = if (i.name != "this") {
    super.storeIdentifierTypeInfo(i, types)
  }

  override protected def storeCallTypeInfo(c: Call, types: Seq[String]): Unit =
    if (types.nonEmpty) {
      state.changesWereMade.compareAndSet(false, true)
      val signedTypes = types.map {
        case t if t.endsWith(c.signature) => t
        case t                            => s"$t:${c.signature}"
      }
      builder.setNodeProperty(c, PropertyNames.POSSIBLE_TYPES, signedTypes)
    }

}
