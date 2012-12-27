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
package com.precog.shard
package service

import com.precog.daze._
import com.precog.common._
import com.precog.common.security._

import blueeyes.core.data._
import blueeyes.core.http._
import blueeyes.core.http.HttpStatusCodes._
import blueeyes.core.service._
import blueeyes.json._
import blueeyes.util.Clock

import akka.dispatch.Future

import com.weiglewilczek.slf4s.Logging

import scalaz._
import scalaz.std.string._
import scalaz.syntax.validation._
import scalaz.syntax.apply._

class BrowseServiceHandler[A](metadataClient: MetadataClient[Future], accessControl: AccessControl[Future])(implicit M: Monad[Future])
extends CustomHttpService[A, (APIKey, Path) => Future[HttpResponse[QueryResult]]] with Logging {
  val service = (request: HttpRequest[A]) => { 
    Success((apiKey: APIKey, path: Path) => {
      metadataClient.browse(apiKey, path) flatMap { browseResult => browseResult match {
        case Success(_) =>
          metadataClient.structure(apiKey, path) map { structureResult =>
            (browseResult |@| structureResult) { (children, structure) =>
              JObject(
                JField("children", children) ::
                Nil
              )
            } match {
              case Success(response) =>
                HttpResponse[QueryResult](OK, content = Some(Left(response)))
               case Failure(error) =>
                HttpResponse[QueryResult](HttpStatus(BadRequest, error))
            }
          }
        case Failure(error) =>
          M.point(HttpResponse[QueryResult](HttpStatus(BadRequest, error)))
      }}
    })
  }

  val metadata = Some(DescriptionMetadata(
"""
Browse the children of the given path. 
"""
  ))
}
