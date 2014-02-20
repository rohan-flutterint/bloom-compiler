package edu.berkeley.cs.boom.bloomscala.codegen.js

import edu.berkeley.cs.boom.bloomscala.codegen.dataflow._
import edu.berkeley.cs.boom.bloomscala.codegen.dataflow.HashEquiJoinElement
import edu.berkeley.cs.boom.bloomscala.codegen.dataflow.Table
import edu.berkeley.cs.boom.bloomscala.parser.BloomPrettyPrinter

/**
 * Compiles Bloom programs to Javascript that use the RxJs and RxFlow libraries.
 *
 * The output contains a Javascript object that exposes Bloom collections as
 * objects that implement the Rx Observable and Observer interfaces.
 *
 * myObj.collectionNameIn is an Observer that can be used to push new tuples
 * into the system by calling myObj.collectionNameIn.onNext(tuple).
 *
 * myObj.collectionNameOut is a Subject that can be subscribed() to
 * in order to receive callbacks when new tuples are added to collections.
 */
object RxFlowCodeGenerator extends DataflowCodeGenerator with JsCodeGeneratorUtils {

  private def elemName(elem: DataflowElement): Doc = {
    elem match {
      case Table(collection) => dquotes(collection.name)
      case InputElement(collection) => dquotes(collection.name)
      case e => text(e.id.toString)
    }
  }

  private def portName(inputPort: InputPort): Doc = {
    val elemRef = "elements" <> brackets(elemName(inputPort.elem))
    elemRef <> dot <> (inputPort.elem match {
      case HashEquiJoinElement(_, _, _) =>
        inputPort.name match {
          case "leftInput" => "leftInput"
          case "rightInput" => "rightInput"
        }
      case Table(collection) =>
        inputPort.name match {
          case "deltaIn" => "Delta"
        }
      case _ => "input"
    })
  }

  private def portName(outputPort: OutputPort): Doc = {
    val elemRef = "elements" <> brackets(elemName(outputPort.elem))
    elemRef <> dot <> "output"
  }

  private def buildInvalidationAndRescanLookupTables(graph: DataflowGraph): Doc = {
    val invalidations = graph.invalidationLookupTable.map { case (k, v) => (elemName(k), arrayLiteral(v.map(elemName))) }
    val rescans = graph.rescanLookupTable.map { case (k, v) => (elemName(k), arrayLiteral(v.map(elemName))) }
    "var" <+> "invalidationLookupTable" <+> equal <+> mapLiteral(invalidations) <> semi <@@> line <>
    "var" <+> "rescanLookupTable" <+> equal <+> mapLiteral(rescans) <> semi
  }

  private def buildTables(graph: DataflowGraph): Doc = {
    val tables = graph.tables.values.map { table =>
      (elemName(table), "new" <+> methodCall("rxflow", "Table", table.lastKeyColIndex.toString) <+>
        comment(BloomPrettyPrinter.pretty(table.collection)))
    }.toMap

    "var" <+> "tables" <+> equal <+> mapLiteral(tables) <> semi
  }

  private def buildInputs(graph: DataflowGraph): Doc = {
    // TODO: this should create an Rx observer, not a Subject.
    val inputs = graph.inputs.values.map { input =>
      (elemName(input), "new" <+> methodCall("rx", "Subject") <+>
        comment(BloomPrettyPrinter.pretty(input.collection)))
    }.toMap

    "var" <+> "inputs" <+> equal <+> mapLiteral(inputs) <> semi <@@>
    graph.inputs.values.map { input =>
      "this" <> dot <> input.collection.name <+> equal <+> "inputs" <> brackets(elemName(input)) <> semi
    }.foldLeft(empty)(_ <@@> _)
  }

  private def buildElement(elem: DataflowElement): Doc = {
    elem match {
      case hj @ HashEquiJoinElement(buildKey, probeKey, leftIsBuild) =>
        "new" <+> methodCall("rxflow", "HashJoin",
            genLambda(buildKey, List("x")),
            genLambda(probeKey, List("x")),
            if (leftIsBuild) "\"left\"" else "\"right\""
          )
      case mapElem @ MapElement(mapFunction, functionArity) =>
        val exprParameterNames =
          if (functionArity == 1) List("x")
          else (0 to functionArity - 1).map(x => s"x[$x]").toList
        "new" <+> methodCall("rxflow", "Map", genLambda(mapFunction, List("x"), Some(exprParameterNames)))
      case Scanner(scannableElem) =>
        scannableElem match {
          case table: Table =>
            methodCall("tables" <> brackets(elemName(table)), "getScanner")
          case input: InputElement =>
            methodCall("inputs" <> brackets(elemName(input)), "getScanner")
        }
      case elem => elemName(elem)  // TODO: remove this, since it's suppressing test failures
    }
  }

  private def buildElements(graph: DataflowGraph): Doc = {
    // TODO: handle strata
    val elements = graph.stratifiedElements.flatMap(_._2.filterNot(_.isInstanceOf[ScannableDataflowElement]).toSeq)
    val elementsMap = elements.map { elem =>
      (elemName(elem), buildElement(elem))
    }.toMap

    "var" <+> "elements" <+> equal <+> mapLiteral(elementsMap) <> semi
  }

  private def wireElements(graph: DataflowGraph): Doc = {
    val elements = graph.stratifiedElements.flatMap(_._2.filterNot(_.isInstanceOf[Table]).toSeq)
    elements.flatMap { elem =>
      elem.outputPorts.flatMap { outputPort => outputPort.connections.map { case Edge(_, inputPort) =>
        portName(outputPort) <> dot <> functionCall("subscribe", portName(inputPort)) <> semi
      }}
    }.reduce(_ <@@> _)
  }

  override def generateCode(graph: DataflowGraph): CharSequence = {

    val code = "function Bloom ()" <+> braces(nest(
      linebreak <>
      buildTables(graph) <@@>
      linebreak <>
      buildInputs(graph) <@@>
      linebreak <>
      buildElements(graph) <@@>
      linebreak <>
      buildInvalidationAndRescanLookupTables(graph) <@@>
      linebreak <>
      wireElements(graph)
    ) <> linebreak)
    super.pretty(code)
  }

}
