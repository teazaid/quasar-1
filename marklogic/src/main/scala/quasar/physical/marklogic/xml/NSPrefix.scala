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

package quasar.physical.marklogic.xml

import quasar.physical.marklogic.validation.IsNCName

import eu.timepit.refined.refineMV
import scalaz.{Order, Show}
import scalaz.syntax.show._

/** A namespace prefix like `xs` or `fn`. */
final case class NSPrefix(value: NCName) extends scala.AnyVal {
  def apply(local: NCName): QName = QName.prefixed(this, local)
  override def toString = this.shows
}

object NSPrefix {
  val local: NSPrefix =
    NSPrefix(NCName(refineMV[IsNCName]("local")))

  implicit val order: Order[NSPrefix] =
    Order.orderBy(_.value)

  implicit val show: Show[NSPrefix] =
    Show.shows(_.value.shows)
}
