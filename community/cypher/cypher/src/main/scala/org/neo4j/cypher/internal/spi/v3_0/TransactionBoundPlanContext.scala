/*
 * Copyright (c) 2002-2016 "Neo Technology,"
 * Network Engine for Objects in Lund AB [http://neotechnology.com]
 *
 * This file is part of Neo4j.
 *
 * Neo4j is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.neo4j.cypher.internal.spi.v3_0

import org.neo4j.cypher.MissingIndexException
import org.neo4j.cypher.internal.LastCommittedTxIdProvider
import org.neo4j.cypher.internal.compiler.v3_0.pipes.EntityProducer
import org.neo4j.cypher.internal.compiler.v3_0.pipes.matching.ExpanderStep
import org.neo4j.cypher.internal.compiler.v3_0.spi._
import org.neo4j.cypher.internal.frontend.v3_0.symbols
import org.neo4j.cypher.internal.frontend.v3_0.symbols.CypherType
import org.neo4j.graphdb.{GraphDatabaseService, Node}
import org.neo4j.kernel.api.Statement
import org.neo4j.kernel.api.constraints.UniquenessConstraint
import org.neo4j.kernel.api.exceptions.KernelException
import org.neo4j.kernel.api.exceptions.schema.SchemaKernelException
import org.neo4j.kernel.api.index.{IndexDescriptor, InternalIndexState}
import org.neo4j.kernel.api.proc.Neo4jTypes.AnyType
import org.neo4j.kernel.api.proc.{Neo4jTypes, ProcedureSignature => KernelProcedureSignature}

import scala.collection.JavaConverters._

class TransactionBoundPlanContext(initialStatement: Statement, val gdb: GraphDatabaseService)
  extends TransactionBoundTokenContext(initialStatement) with PlanContext {

  @Deprecated
  def getIndexRule(labelName: String, propertyKey: String): Option[IndexDescriptor] = evalOrNone {
    val labelId = _statement.readOperations().labelGetForName(labelName)
    val propertyKeyId = _statement.readOperations().propertyKeyGetForName(propertyKey)

    getOnlineIndex(_statement.readOperations().indexGetForLabelAndPropertyKey(labelId, propertyKeyId))
  }

  def hasIndexRule(labelName: String): Boolean = {
    val labelId = _statement.readOperations().labelGetForName(labelName)

    val indexDescriptors = _statement.readOperations().indexesGetForLabel(labelId).asScala
    val onlineIndexDescriptors = indexDescriptors.flatMap(getOnlineIndex)

    onlineIndexDescriptors.nonEmpty
  }

  def getUniqueIndexRule(labelName: String, propertyKey: String): Option[IndexDescriptor] = evalOrNone {
    val labelId = _statement.readOperations().labelGetForName(labelName)
    val propertyKeyId = _statement.readOperations().propertyKeyGetForName(propertyKey)

    // here we do not need to use getOnlineIndex method because uniqueness constraint creation is synchronous
    Some(_statement.readOperations().uniqueIndexGetForLabelAndPropertyKey(labelId, propertyKeyId))
  }

  private def evalOrNone[T](f: => Option[T]): Option[T] =
    try { f } catch { case _: SchemaKernelException => None }

  private def getOnlineIndex(descriptor: IndexDescriptor): Option[IndexDescriptor] =
    _statement.readOperations().indexGetState(descriptor) match {
      case InternalIndexState.ONLINE => Some(descriptor)
      case _                         => None
    }

  def getUniquenessConstraint(labelName: String, propertyKey: String): Option[UniquenessConstraint] = try {
    val labelId = _statement.readOperations().labelGetForName(labelName)
    val propertyKeyId = _statement.readOperations().propertyKeyGetForName(propertyKey)

    val matchingConstraints = _statement.readOperations().constraintsGetForLabelAndPropertyKey(labelId, propertyKeyId)

    import scala.collection.JavaConverters._
    _statement.readOperations().constraintsGetForLabelAndPropertyKey(labelId, propertyKeyId).asScala.collectFirst {
      case unique: UniquenessConstraint => unique
    }
  } catch {
    case _: KernelException => None
  }

  def checkNodeIndex(idxName: String) {
    if (!gdb.index().existsForNodes(idxName)) {
      throw new MissingIndexException(idxName)
    }
  }

  def checkRelIndex(idxName: String)  {
    if ( !gdb.index().existsForRelationships(idxName) ) {
      throw new MissingIndexException(idxName)
    }
  }

  def getOrCreateFromSchemaState[T](key: Any, f: => T): T = {
    val javaCreator = new java.util.function.Function[Any, T]() {
      def apply(key: Any) = f
    }
    _statement.readOperations().schemaStateGetOrCreate(key, javaCreator)
  }


  // Legacy traversal matchers (pre-Ronja) (These were moved out to remove the dependency on the kernel)
  override def monoDirectionalTraversalMatcher(steps: ExpanderStep, start: EntityProducer[Node]) =
    new MonoDirectionalTraversalMatcher(steps, start)

  override def bidirectionalTraversalMatcher(steps: ExpanderStep,
                                             start: EntityProducer[Node],
                                             end: EntityProducer[Node]) =
    new BidirectionalTraversalMatcher(steps, start, end)

  val statistics: GraphStatistics =
    InstrumentedGraphStatistics(TransactionBoundGraphStatistics(_statement), MutableGraphStatisticsSnapshot())

  val txIdProvider = LastCommittedTxIdProvider(gdb)

  override def procedureSignature(name: ProcedureName) = {
    val kn = new KernelProcedureSignature.ProcedureName(name.namespace.asJava, name.name)
    val ks = _statement.readOperations().procedureGet(kn)
    val input = ks.inputSignature().asScala.map(s => FieldSignature(s.name(), asCypherType(s.neo4jType())))
    val output = ks.outputSignature().asScala.map(s => FieldSignature(s.name(), asCypherType(s.neo4jType())))

    ProcedureSignature(name, input, output)
  }

  private def asCypherType(neoType: AnyType): CypherType = neoType match {
    case Neo4jTypes.NTString => symbols.CTString
    case Neo4jTypes.NTInteger => symbols.CTInteger
    case Neo4jTypes.NTFloat => symbols.CTFloat
    case Neo4jTypes.NTNumber => symbols.CTNumber
    case Neo4jTypes.NTBoolean => symbols.CTBoolean
    case l: Neo4jTypes.ListType => symbols.CTCollection(asCypherType(l.innerType()))
    case Neo4jTypes.NTMap => symbols.CTMap
    case Neo4jTypes.NTNode => symbols.CTNode
    case Neo4jTypes.NTRelationship => symbols.CTRelationship
    case Neo4jTypes.NTPath => symbols.CTPath
  }
}
