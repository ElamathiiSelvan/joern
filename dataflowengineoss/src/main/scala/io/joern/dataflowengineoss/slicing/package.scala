package io.joern.dataflowengineoss

import better.files.File
import io.circe.{Decoder, Encoder, HCursor, Json}
import io.joern.dataflowengineoss.slicing.SliceMode.SliceModes
import io.shiftleft.codepropertygraph.generated.PropertyNames
import io.shiftleft.codepropertygraph.generated.nodes._

package object slicing {

  import io.circe.generic.auto._
  import io.circe.syntax.EncoderOps

  /** The kind of mode to use for slicing.
    */
  object SliceMode extends Enumeration {
    type SliceModes = Value
    val DataFlow, Usages = Value
  }

  case class SliceConfig(
    inputPath: File = File("cpg.bin"),
    outFile: File = File("slices"),
    sliceMode: SliceModes = SliceMode.DataFlow,
    sourceFile: Option[String] = None,
    sliceDepth: Int = 20,
    minNumCalls: Int = 1,
    typeRecoveryDummyTypes: Boolean = false,
    excludeOperatorCalls: Boolean = false
  )

  /** A trait for all objects that represent a 1:1 relationship between the CPG and all the slices extracted.
    */
  sealed trait ProgramSlice {

    def toJson: String

    def toJsonPretty: String

  }

  /** A data-flow slice vector for a given backwards intraprocedural path.
    *
    * @param nodes
    *   the nodes in the slice.
    * @param edges
    *   a map linking nodes with their edges.
    * @param methodToChildNode
    *   a mapping between method names and which nodes fall under them.
    */
  case class DataFlowSlice(nodes: Set[SliceNode], edges: Set[SliceEdge], methodToChildNode: Map[String, Set[Long]])

  implicit val encodeDataFlowSlice: Encoder[DataFlowSlice] = Encoder.instance {
    case DataFlowSlice(nodes, edges, methodToChildNode) =>
      Json.obj("nodes" -> nodes.asJson, "edges" -> edges.asJson, "methodToChildNode" -> methodToChildNode.asJson)
  }

  case class SliceNode(
    id: Long,
    label: String,
    name: String = "",
    code: String,
    typeFullName: String = "",
    lineNumber: Integer = -1,
    columnNumber: Integer = -1
  )

  implicit val encodeSliceNode: Encoder[SliceNode] = Encoder.instance {
    case SliceNode(id, label, name, code, typeFullName, lineNumber, columnNumber) =>
      Json.obj(
        "id"           -> id.asJson,
        "label"        -> label.asJson,
        "name"         -> name.asJson,
        "code"         -> code.asJson,
        "typeFullName" -> typeFullName.asJson,
        "lineNumber"   -> lineNumber.asJson,
        "columnNumber" -> columnNumber.asJson
      )
  }

  case class SliceEdge(src: Long, dst: Long, label: String)

  implicit val encodeSliceEdge: Encoder[SliceEdge] = Encoder.instance { case SliceEdge(src, dst, label) =>
    Json.obj("src" -> src.asJson, "dst" -> dst.asJson, "label" -> label.asJson)
  }

  /** The data-flow slices for the program grouped by procedure.
    *
    * @param dataFlowSlices
    *   the mapped slices.
    */
  case class ProgramDataFlowSlice(dataFlowSlices: Map[String, Set[DataFlowSlice]]) extends ProgramSlice {

    def toJson: String = this.asJson.toString()

    def toJsonPretty: String = this.asJson.spaces2

  }

  /** A usage slice of an object at the start of its definition until its final usage.
    *
    * @param targetObj
    *   the name and type of the focus object.
    * @param definedBy
    *   the name of the call, identifier, or literal that defined the target object, if available.
    * @param invokedCalls
    *   calls this object is observed to call.
    * @param argToCalls
    *   the calls this object is observed to be an argument of.
    */
  case class ObjectUsageSlice(
    targetObj: DefComponent,
    definedBy: Option[DefComponent],
    invokedCalls: List[ObservedCall],
    argToCalls: List[(ObservedCall, Int)]
  ) {
    override def toString: String =
      s"{tgt: $targetObj${definedBy.map(p => s" = $p").getOrElse("")}, " +
        s"inv: [${invokedCalls.mkString(",")}], " +
        s"argsTo: [${argToCalls.map { case (callArg: ObservedCall, idx: Int) => s"$callArg@$idx" }.mkString(",")}]" +
        s"}"
  }

  implicit val decodeObjectUsageSlice: Decoder[ObjectUsageSlice] =
    (c: HCursor) =>
      for {
        x <- c.downField("targetObj").as[DefComponent]
        p <- c.downField("definedBy").as[Option[DefComponent]]
        r <- c.downField("invokedCalls").as[List[ObservedCall]]
        a <- c.downField("argToCalls").as[List[(ObservedCall, Int)]]
      } yield {
        ObjectUsageSlice(x, p, r, a)
      }
  implicit val encodeObjectUsageSlice: Encoder[ObjectUsageSlice] =
    Encoder.instance { case ObjectUsageSlice(c, p, r, a) =>
      Json.obj("targetObj" -> c.asJson, "definedBy" -> p.asJson, "invokedCalls" -> r.asJson, "argToCalls" -> a.asJson)
    }

  /** Represents a component that carries data. This could be an identifier of a variable or method and supplementary
    * type information, if available.
    *
    * @param name
    *   the name of the object or method call.
    * @param typeFullName
    *   the type full name.
    * @param literal
    *   if this object represents a literal or not.
    */
  case class DefComponent(name: String, typeFullName: String, literal: Boolean = false) {
    override def toString: String = s"$name" +
      (if (typeFullName.nonEmpty) s": $typeFullName" else "") +
      (if (literal) " [LITERAL]" else "")
  }

  implicit val decodeDefComponent: Decoder[DefComponent] =
    (c: HCursor) =>
      for {
        x <- c.downField("name").as[String]
        p <- c.downField("typeFullName").as[String]
        r <- c.downField("literal").as[Boolean]
      } yield {
        DefComponent(x, p, r)
      }
  implicit val encodeDefComponent: Encoder[DefComponent] =
    Encoder.instance { case DefComponent(c, p, r) =>
      Json.obj("name" -> c.asJson, "typeFullName" -> p.asJson, "literal" -> r.asJson)
    }

  object DefComponent {

    /** Attempts to generate an [[DefComponent]] from the given CPG node.
      *
      * @param node
      *   the CPG node.
      * @return
      *   an ID type pair with default values "UNKNOWN" if the respective properties for [[DefComponent]] could not be
      *   extracted.
      */
    def fromNode(node: StoredNode): DefComponent = {
      val name = node match {
        case x: TypeDecl          => x.name
        case x: MethodParameterIn => x.name
        case x: Call              => x.code.takeWhile(_ != '(')
        case x: Identifier        => x.name
        case x: Member            => x.name
        case x: AstNode           => x.code
        case _                    => "UNKNOWN"
      }
      val typs = node.property(PropertyNames.TYPE_FULL_NAME, "ANY") +: node.property(
        PropertyNames.DYNAMIC_TYPE_HINT_FULL_NAME,
        Seq.empty[String]
      )
      DefComponent(
        name,
        typs.filterNot(_.matches("(ANY|UNKNOWN)")).headOption.getOrElse("ANY"),
        node.label.equals(Literal.Label)
      )
    }
  }

  /** Details related to an observed call.
    *
    * @param callName
    *   the name of the call.
    * @param paramTypes
    *   the observed parameter types.
    * @param returnType
    *   the observed return type.
    */
  case class ObservedCall(callName: String, paramTypes: List[String], returnType: String) {
    override def toString: String =
      s"$callName(${paramTypes.mkString(",")}):$returnType"
  }

  implicit val decodeObservedCall: Decoder[ObservedCall] =
    (c: HCursor) =>
      for {
        x <- c.downField("callName").as[String]
        p <- c.downField("paramTypes").as[List[String]]
        r <- c.downField("returnType").as[String]
      } yield {
        ObservedCall(x, p, r)
      }
  implicit val encodeObservedCall: Encoder[ObservedCall] =
    Encoder.instance { case ObservedCall(c, p, r) =>
      Json.obj("callName" -> c.asJson, "paramTypes" -> p.asJson, "returnType" -> r.asJson)
    }

  /** Describes types defined within the application.
    *
    * @param name
    *   name of the type.
    * @param fields
    *   the static or object fields.
    * @param procedures
    *   defined, named procedures within the type.
    */
  case class UserDefinedType(name: String, fields: List[DefComponent], procedures: List[ObservedCall])

  implicit val decodeUserDefinedType: Decoder[UserDefinedType] =
    (c: HCursor) =>
      for {
        n <- c.downField("name").as[String]
        f <- c.downField("fields").as[List[DefComponent]]
        p <- c.downField("procedures").as[List[ObservedCall]]
      } yield {
        UserDefinedType(n, f, p)
      }
  implicit val encodeUserDefinedType: Encoder[UserDefinedType] =
    Encoder.instance { case UserDefinedType(n, f, p) =>
      Json.obj("name" -> n.asJson, "fields" -> f.asJson, "procedures" -> p.asJson)
    }

  /** The program usage slices and UDTs.
    *
    * @param objectSlices
    *   the object slices under each procedure
    * @param userDefinedTypes
    *   the UDTs.
    */
  case class ProgramUsageSlice(
    objectSlices: Map[String, Set[ObjectUsageSlice]],
    userDefinedTypes: List[UserDefinedType]
  ) extends ProgramSlice {

    def toJson: String = this.asJson.toString()

    def toJsonPretty: String = this.asJson.spaces2
  }

  implicit val decodeProgramUsageSlice: Decoder[ProgramUsageSlice] =
    (c: HCursor) =>
      for {
        o <- c.downField("objectSlices").as[Map[String, Set[ObjectUsageSlice]]]
        u <- c.downField("userDefinedTypes").as[List[UserDefinedType]]
      } yield {
        ProgramUsageSlice(o, u)
      }
  implicit val encodeProgramUsageSlice: Encoder[ProgramUsageSlice] = Encoder.instance {
    case ProgramUsageSlice(os, udts) => Json.obj("objectSlices" -> os.asJson, "userDefinedTypes" -> udts.asJson)
  }

  /** The inference response from the server.
    */
  case class InferenceResult(
    targetIdentifier: String,
    typ: String,
    confidence: Float,
    scope: String,
    alternatives: List[AlternativeResult]
  )

  implicit val decodeInferenceResult: Decoder[InferenceResult] = (c: HCursor) =>
    for {
      targetIdentifier <- c.downField("target_identifier").as[String]
      typ              <- c.downField("type").as[String]
      confidence       <- c.downField("confidence").as[Float]
      scope            <- c.downField("scope").as[String]
      alternatives     <- c.downField("alternatives").as[List[AlternativeResult]]
    } yield {
      InferenceResult(targetIdentifier, typ, confidence, scope, alternatives)
    }

  implicit val encodeInferenceResult: Encoder[InferenceResult] = Encoder.instance {
    case InferenceResult(targetIdentifier, typ, confidence, scope, alternatives) =>
      Json.obj(
        "target_identifier" -> targetIdentifier.asJson,
        "type"              -> typ.asJson,
        "confidence"        -> confidence.asJson,
        "scope"             -> scope.asJson,
        "alternatives"      -> alternatives.asJson
      )
  }

  /** Alternative type inference results.
    */
  case class AlternativeResult(typ: String, confidence: Float)

  implicit val decodeAlternativeResult: Decoder[AlternativeResult] = (c: HCursor) =>
    for {
      typ        <- c.downField("type").as[String]
      confidence <- c.downField("confidence").as[Float]
    } yield {
      AlternativeResult(typ, confidence)
    }

  implicit val encodeAlternativeResult: Encoder[AlternativeResult] = Encoder.instance {
    case AlternativeResult(typ, confidence) =>
      Json.obj("type" -> typ.asJson, "confidence" -> confidence.asJson)
  }

}
