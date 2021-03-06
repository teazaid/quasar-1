/*
 * Copyright 2014–2017 SlamData Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package quasar.main

import slamdata.Predef.{Int => SInt, _}
import quasar.Data
import quasar.contrib.matryoshka._
import quasar.ejson.{EJson, Fixed}
import quasar.ejson.implicits._
import quasar.fp._
import quasar.fp.numeric.SampleStats
import quasar.sst._
import quasar.tpe._

import eu.timepit.refined.auto._
import matryoshka.data.Fix
import matryoshka.implicits._
import scalaz._, Scalaz._
import scalaz.stream.Process
import spire.std.double._

final class ExtractSchemaSpec extends quasar.Qspec {
  import Data._, StructuralType.TypeST

  type J = Fix[EJson]
  type S = SST[J, Double]

  val J = Fixed[J]
  val settings = analysis.CompressionSettings(1000L, 1000L, 1000L, 1000L)

  def verify(cs: analysis.CompressionSettings, input: List[Data], expected: S) =
    Process.emitAll(input)
      .pipe(analysis.extractSchema[J, Double](cs))
      .toVector.headOption must beSome(equal(expected))

  def ints(n: SInt, ns: SInt*): NonEmptyList[S] =
    NonEmptyList(n, ns: _*) map (n =>
      envT(
        TypeStat.int(SampleStats.one(n.toDouble), BigInt(n), BigInt(n)),
        TypeST(TypeF.const[J, S](J.int(BigInt(n))))).embed)

  "compress arrays" >> {
    val input = List(
      _obj(ListMap("foo" -> _arr(List(_int(1), _int(2))))),
      _obj(ListMap("bar" -> _arr(List(_int(1), _int(2), _int(3)))))
    )

    val expected = envT(
      TypeStat.coll(2.0, 1.0.some, 1.0.some),
      TypeST(TypeF.map[J, S](IMap(
        J.str("foo") -> envT(
          TypeStat.coll(1.0, 2.0.some, 2.0.some),
          TypeST(TypeF.arr[J, S](ints(1, 2).list.left))).embed,

        J.str("bar") -> envT(
          TypeStat.coll(1.0, 3.0.some, 3.0.some),
          TypeST(TypeF.arr[J, S](ints(1, 2, 3).suml1.right))).embed
      ), None))).embed

    verify(settings.copy(arrayMaxLength = 2L), input, expected)
  }

  "compress long strings" >> {
    val input = List(
      _obj(ListMap("foo" -> _str("abcdef"))),
      _obj(ListMap("bar" -> _str("abcde")))
    )

    val expected = envT(
      TypeStat.coll(2.0, 1.0.some, 1.0.some),
      TypeST(TypeF.map[J, S](IMap(
        J.str("foo") -> envT(
          TypeStat.coll(1.0, 6.0.some, 6.0.some),
          TypeST(TypeF.arr[J, S](envT(
            TypeStat.count(1.0),
            TypeST(TypeF.simple[J, S](SimpleType.Char))
          ).embed.right))).embed,

        J.str("bar") -> envT(
          TypeStat.str(1.0, 5.0, 5.0, "abcde", "abcde"),
          TypeST(TypeF.const[J, S](J.str("abcde")))
        ).embed
      ), None))).embed

    verify(settings.copy(stringMaxLength = 5L), input, expected)
  }

  "compress encoded binary strings" >> {
    val b1 = ImmutableArray.fromArray("".getBytes)
    val l1 = b1.length.toDouble
    val b2 = ImmutableArray.fromArray("abcdef".getBytes)
    val l2 = b2.length.toDouble

    val input = List(
      _obj(ListMap("foo" -> _binary(b1))),
      _obj(ListMap("bar" -> _binary(b2)))
    )

    val expected = envT(
      TypeStat.coll(2.0, 1.0.some, 1.0.some),
      TypeST(TypeF.map[J, S](IMap(
        J.str("foo") -> envT(
          TypeStat.coll(1.0, l1.some, l1.some),
          TypeST(TypeF.arr[J, S](envT(
            TypeStat.count(1.0),
            TypeST(TypeF.simple[J, S](SimpleType.Byte))
          ).embed.right))).embed,

        J.str("bar") -> envT(
          TypeStat.coll(1.0, l2.some, l2.some),
          TypeST(TypeF.arr[J, S](envT(
            TypeStat.count(1.0),
            TypeST(TypeF.simple[J, S](SimpleType.Byte))
          ).embed.right))).embed
      ), None))).embed

    verify(settings, input, expected)
  }

  "coalesce map keys until <= max size" >> {
    val input = List(
      _obj(ListMap("foo" -> _int(1))),
      _obj(ListMap("bar" -> _int(1))),
      _obj(ListMap("baz" -> _int(1))),
      _obj(ListMap("quux" -> _int(1)))
    )

    val expected = envT(
      TypeStat.coll(4.0, 1.0.some, 1.0.some),
      TypeST(TypeF.map[J, S](IMap.empty[J, S], Some((
        envT(
          TypeStat.str(4.0, 3.0, 4.0, "bar", "quux"),
          TypeST(TypeF.arr[J, S](envT(
            TypeStat.count(4.0),
            TypeST(TypeF.simple[J, S](SimpleType.Char))
          ).embed.right))).embed,
        envT(
          TypeStat.int(SampleStats.freq(4.0, 1.0), BigInt(1), BigInt(1)),
          TypeST(TypeF.const[J, S](J.int(1)))
        ).embed
      ))))).embed

    verify(settings.copy(mapMaxSize = 3L, unionMaxSize = 1L), input, expected)
  }

  "coalesce new map keys when primary type in unknown key" >> {
    val input = List(
      _obj(ListMap("foo" -> _int(1))),
      _obj(ListMap("bar" -> _int(1))),
      _obj(ListMap("baz" -> _int(1))),
      _obj(ListMap("quux" -> _int(1)))
    )

    val expected = envT(
      TypeStat.coll(4.0, 1.0.some, 1.0.some),
      TypeST(TypeF.map[J, S](IMap.empty[J, S], Some((
        envT(
          TypeStat.str(4.0, 3.0, 4.0, "bar", "quux"),
          TypeST(TypeF.arr[J, S](envT(
            TypeStat.count(4.0),
            TypeST(TypeF.simple[J, S](SimpleType.Char))
          ).embed.right))).embed,
        envT(
          TypeStat.int(SampleStats.freq(4.0, 1.0), BigInt(1), BigInt(1)),
          TypeST(TypeF.const[J, S](J.int(1)))
        ).embed
      ))))).embed

    verify(settings.copy(mapMaxSize = 2L, unionMaxSize = 1L), input, expected)
  }

  "narrow unions until <= max size" >> {
    val input = List(
      _obj(ListMap("foo" -> _int(1))),
      _obj(ListMap("foo" -> _int(2))),
      _obj(ListMap("foo" -> _bool(true))),
      _obj(ListMap("foo" -> _bool(false)))
    )

    val expected = envT(
      TypeStat.coll(4.0, 1.0.some, 1.0.some),
      TypeST(TypeF.map[J, S](IMap(
        J.str("foo") -> envT(
          TypeStat.count(4.0),
          TypeST(TypeF.coproduct[J, S](
            envT(
              TypeStat.int(
                SampleStats.fromFoldable(IList(1.0, 2.0)),
                BigInt(1),
                BigInt(2)),
              TypeST(TypeF.simple[J, S](SimpleType.Int))).embed,
            envT(
              TypeStat.bool(1.0, 1.0),
              TypeST(TypeF.simple[J, S](SimpleType.Bool))).embed))
        ).embed
      ), None))).embed

    verify(settings.copy(unionMaxSize = 2L), input, expected)
  }

  "coalesce consts with primary types in unions" >> {
    val input = List(
      _obj(ListMap("foo" -> _int(1))),
      _obj(ListMap("foo" -> _int(2))),
      _obj(ListMap("foo" -> _bool(true))),
      _obj(ListMap("foo" -> _int(3)))
    )

    val expected = envT(
      TypeStat.coll(4.0, 1.0.some, 1.0.some),
      TypeST(TypeF.map[J, S](IMap(
        J.str("foo") -> envT(
          TypeStat.count(4.0),
          TypeST(TypeF.coproduct[J, S](
            envT(
              TypeStat.int(
                SampleStats.fromFoldable(IList(1.0, 2.0, 3.0)),
                BigInt(1),
                BigInt(3)),
              TypeST(TypeF.simple[J, S](SimpleType.Int))).embed,
            envT(
              TypeStat.bool(1.0, 0.0),
              TypeST(TypeF.const[J, S](J.bool(true)))).embed))
        ).embed
      ), None))).embed

    verify(settings.copy(unionMaxSize = 2L), input, expected)
  }
}
