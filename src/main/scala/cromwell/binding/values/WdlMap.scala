package cromwell.binding.values

import cromwell.binding.types.{WdlAnyType, WdlMapType, WdlPrimitiveType, WdlType}
import cromwell.util.{FileUtil, TryUtil}

import scala.util.{Failure, Success, Try}

object WdlMap {
  def coerceMap(m: Map[_, _], wdlMapType: WdlMapType): WdlMap = {
    val coerced = m map { case(k, v) => wdlMapType.keyType.coerceRawValue(k) -> wdlMapType.valueType.coerceRawValue(v) }
    val failures = coerced flatMap { case(k,v) => Seq(k,v) } collect { case f:Failure[_] => f }
    failures match {
      case f: Iterable[Failure[_]] if f.nonEmpty =>
        throw new UnsupportedOperationException(s"Failed to coerce one or more keys or values for creating a ${wdlMapType.toWdlString}:\n${TryUtil.stringifyFailures(f)}}")
      case _ =>
        val mapCoerced = coerced map { case (k, v) => k.get -> v.get }

        // Yes, throw an exception if keyType or valueType can't be determined
        val keyType = WdlType.homogeneousType(mapCoerced map { case (k, v) => k }).get
        val valueType = WdlType.homogeneousType(mapCoerced map { case (k, v) => v }).get

        WdlMap(WdlMapType(keyType, valueType), mapCoerced)
    }
  }

  def fromTsv(tsv: String, wdlMapType: WdlMapType = WdlMapType(WdlAnyType, WdlAnyType)): Try[WdlMap] = {
    FileUtil.parseTsv(tsv) match {
      case Success(table) if table.isEmpty => Success(WdlMap(wdlMapType, Map.empty[WdlValue, WdlValue]))
      case Success(table) if table.head.length != 2 => Failure(new UnsupportedOperationException("TSV must be 2 columns to convert to a Map"))
      case Success(table) => Try(coerceMap(table.map(row => row(0) -> row(1)).toMap, wdlMapType))
      case Failure(e) => Failure(e)
    }
  }
}

case class WdlMap(wdlType: WdlMapType, value: Map[WdlValue, WdlValue]) extends WdlValue {
  val typesUsedInKey = value.map { case (k,v) => k.wdlType }.toSet

  if (typesUsedInKey.size == 1 && typesUsedInKey.head != wdlType.keyType)
    throw new UnsupportedOperationException(s"Could not construct a $wdlType as this value: $value")

  if (typesUsedInKey.size > 1)
    throw new UnsupportedOperationException(s"Cannot construct $wdlType with mixed types: $value")

  val typesUsedInValue = value.map { case (k,v) => v.wdlType }.toSet

  if (typesUsedInValue.size == 1 && typesUsedInValue.head != wdlType.valueType)
    throw new UnsupportedOperationException(s"Could not construct a $wdlType as this value: $value")

  if (typesUsedInValue.size > 1)
    throw new UnsupportedOperationException(s"Cannot construct $wdlType with mixed types: $value")

  override def toWdlString: String =
    "{" + value.map {case (k,v) => s"${k.toWdlString}: ${v.toWdlString}"}.mkString(", ") + "}"

  def tsvSerialize: Try[String] = {
    (wdlType.keyType, wdlType.valueType) match {
      case (k: WdlPrimitiveType, v: WdlPrimitiveType) =>
        Success(value.map({case (k, v) => s"${k.valueString}\t${v.valueString}"}).mkString("\n"))
      case _ =>
        Failure(new UnsupportedOperationException("Can only TSV serialize a Map[Primitive, Primitive]"))
    }
  }
}