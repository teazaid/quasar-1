/*
 *  ____    ____    _____    ____    ___     ____
 * |  _ \  |  _ \  | ____|  / ___|  / _/    / ___|        Precog (R)
 * | |_) | | |_) | |  _|   | |     | |  /| | |  _         Advanced Analytics Engine for NoSQL Data
 * |  __/  |  _ <  | |___  | |___  |/ _| | | |_| |        Copyright (C) 2010 - 2013 SlamData, Inc.
 * |_|     |_| \_\ |_____|  \____|   /__/   \____|        All Rights Reserved.
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version
 * 3 of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY;
 * without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See
 * the GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License along with this
 * program. If not, see <http://www.gnu.org/licenses/>.
 *
 */
package quasar.ygg

import blueeyes._, json._
import com.precog.common._
import scalaz._, Scalaz._
import quasar.precog.TestSupport._
import Gen.{ alphaStr, listOfN, containerOfN, identifier, posNum, oneOf, delay }

object CValueGenerators {
  type JSchema = Seq[JPath -> CType]

  def inferSchema(data: Seq[JValue]): JSchema = data match {
    case Seq()    => Seq()
    case hd +: tl =>
      val current = hd.flattenWithPath flatMap { case (path, jv) => CType.forJValue(jv) map (path -> _) }
      current ++ inferSchema(tl) distinct
  }
}

trait CValueGenerators {
  import CValueGenerators._

  def schema(depth: Int): Gen[JSchema] =
    if (depth <= 0) leafSchema
    else oneOf(delay(objectSchema(depth, choose(1, 3))), delay(arraySchema(depth, choose(1, 5))), leafSchema)

  def objectSchema(depth: Int, sizeGen: Gen[Int]): Gen[JSchema] = {
    for {
      size <- sizeGen
      names <- containerOfN[Set, String](size, identifier)
      subschemas <- listOfN(size, schema(depth - 1))
    } yield {
      for {
        (name, subschema) <- names.toList zip subschemas
        (jpath, ctype)    <- subschema
      } yield {
        (JPathField(name) \ jpath, ctype)
      }
    }
  }

  def arraySchema(depth: Int, sizeGen: Gen[Int]): Gen[JSchema] =
    sizeGen >> { size =>
      schema(depth - 1) * size ^^ { subschemas =>
        0 until size zip subschemas flatMap { case (idx, pairs) =>
          pairs map { case (jpath, ctype) =>
            (JPathIndex(idx) \ jpath) -> ctype
          }
        }
      }
    }

  def leafSchema: Gen[JSchema] = ctype map { t => (NoJPath -> t) :: Nil }

  def ctype: Gen[CType] = oneOf(
    CString,
    CBoolean,
    CLong,
    CDouble,
    CNum,
    CNull,
    CEmptyObject,
    CEmptyArray
  )

  // FIXME: TODO Should this provide some form for CDate?
  def jvalue(ctype: CType): Gen[JValue] = ctype match {
    case CString       => alphaStr map (JString(_))
    case CBoolean      => genBool map (JBool(_))
    case CLong         => genLong map (ln => JNum(decimal(ln)))
    case CDouble       => genDouble map (d => JNum(decimal(d)))
    case CNum          => genBigDecimal map (bd => JNum(bd))
    case CNull         => JNull
    case CEmptyObject  => JObject.empty
    case CEmptyArray   => JArray.empty
    case CUndefined    => JUndefined
    case CArrayType(_) => abort("undefined")
    case CDate         => abort("undefined")
    case CPeriod       => abort("undefined")
  }

  def jvalue(schema: Seq[JPath -> CType]): Gen[JValue] = {
    schema.foldLeft(Gen.const[JValue](JUndefined)) {
      case (gen, (jpath, ctype)) =>
        for {
          acc <- gen
          jv  <- jvalue(ctype)
        } yield {
          acc.unsafeInsert(jpath, jv)
        }
    }
  }

  def genEventColumns(jschema: JSchema): Gen[Int -> Stream[Identities -> Seq[JPath -> JValue]]] =
    for {
      idCount  <- choose(1, 3)
      dataSize <- choose(0, 20)
      ids      <- setOfN[List[Long]](dataSize, listOfN[Long](idCount, genPosLong))
      values   <- listOfN[Seq[JPath -> JValue]](dataSize, Gen.sequence(jschema map { case (k, v) => jvalue(v) map (k -> _) }))
      falseDepth  <- choose(1, 3)
      falseSchema <- schema(falseDepth)
      falseSize   <- choose(0, 5)
      falseIds    <- setOfN[List[Long]](falseSize, listOfN(idCount, genPosLong))
      falseValues <- listOfN[Seq[JPath -> JValue]](falseSize, Gen.sequence(falseSchema map { case (k, v) => jvalue(v).map(k -> _) }))

      falseIds2 = falseIds -- ids     // distinct ids
    } yield {
      (idCount, (ids.map(_.toArray) zip values).toStream ++ (falseIds2.map(_.toArray) zip falseValues).toStream)
    }

  def assemble(parts: Seq[JPath -> JValue]): JValue = {
    val result = parts.foldLeft[JValue](JUndefined) {
      case (acc, (selector, jv)) => acc.unsafeInsert(selector, jv)
    }

    if (result != JUndefined || parts.isEmpty) result else sys.error("Cannot build object from " + parts)
  }
}

trait SValueGenerators {
  private def groupSize = choose(0, 3)

  def svalue(depth: Int): Gen[SValue] =
    if (depth <= 0) sleaf
    else oneOf(delay(sobject(depth)), delay(sarray(depth)), sleaf)

  def sobject(depth: Int): Gen[SValue] =
    groupSize >> (sz => mapOfN(sz, identifier, svalue(depth - 1)) ^^ (SObject(_)))

  def sarray(depth: Int): Gen[SValue] =
    groupSize >> (sz => vectorOfN(sz, svalue(depth - 1)) ^^ (SArray(_)))

  def sleaf: Gen[SValue] = oneOf[SValue](
    alphaStr map (x => SString(x)),
    genBool map (x => SBoolean(x)),
    genLong map (x => SDecimal(BigDecimal(x))),
    genDouble map (x => SDecimal(BigDecimal(x))),
    genBigDecimal map (x => SDecimal(x)),
    SNull
  )

  def sevent(idCount: Int, vdepth: Int): Gen[SEvent] =
    (arrayOfN(idCount, posNum[Long]), svalue(vdepth)).zip

  def chunk(size: Int, idCount: Int, vdepth: Int): Gen[Vector[SEvent]] =
    vectorOfN(size, sevent(idCount, vdepth))
}

final case class LimitList[A](values: List[A])

trait ArbitrarySValue extends SValueGenerators {
  def genSEventChunk: Gen[Vector[SEvent]]                  = chunk(3, 3, 2)
  def genChunks(size: Int): Gen[LimitList[Vector[SEvent]]] = genSEventChunk * choose(0, size) ^^ (LimitList(_))

  implicit lazy val ArbitraryChunks: Arbitrary[LimitList[Vector[SEvent]]] = Arbitrary(genChunks(5))
  implicit lazy val SEventIdentityOrder: Ord[SEvent]                      = Ord[List[Long]] contramap (_._1.toList)
}
