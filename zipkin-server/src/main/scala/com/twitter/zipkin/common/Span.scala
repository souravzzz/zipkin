/*
 * Copyright 2012 Twitter Inc.
 * 
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 */
package com.twitter.zipkin.common

import com.twitter.zipkin.gen
import scala.collection.JavaConversions._
import scala.collection.Map
import java.nio.ByteBuffer

/**
 * A span represents one RPC request. A trace is made up of many spans.
 *
 * A span can contain multiple annotations, some are always incuded such as
 * Client send -> Server received -> Server send -> Client receive.
 *
 * Some are created by users, describing application specific information,
 * such as cache hits/misses.
 */
object Span {

  def apply(span: Span): Span = Span(span.traceId, span.name, span.id,
    span.parentId, span.annotations, span.binaryAnnotations)

  def fromThrift(span: gen.Span): Span = {
    span.`name` match {
      case null => throw new IncompleteTraceDataException("No name set in Span")
      case _ => ()
    }

    val annotations = span.annotations match {
      case null => List[Annotation]()
      case as => as.map { annotation => Annotation.fromThrift(annotation) }.toList
    }

    val binaryAnnotations = span.binaryAnnotations match {
      case null => Seq[gen.BinaryAnnotation]()
      case b => b
    }

    new Span(span.traceId, span.name, span.id, span.parentId, annotations, binaryAnnotations)
  }

}

/**
 * @param traceId random long that identifies the trace, will be set in all spans in this trace
 * @param name name of span, can be rpc method name for example
 * @param id random long that identifies this span
 * @param parentId reference to the parent span in the trace tree
 * @param annotations annotations, containing a timestamp and some value. both user generated and
 * some fixed ones from the tracing framework
 * @param binaryAnnotations  binary annotations, can contain more detailed information such as
 * serialized objects
 */
case class Span(traceId: Long, name: String, id: Long, parentId: Option[Long],
                 annotations: List[Annotation], binaryAnnotations: Seq[gen.BinaryAnnotation]) {
  /**
   * Order annotations by timestamp.
   */
  val timestampOrdering = new Ordering[Annotation] {
      def compare(a: Annotation, b: Annotation) = {a.timestamp.compare(b.timestamp)}
  }

  def toThrift: gen.Span = {
    gen.Span(traceId, name, id, parentId, annotations.map(a => a.toThrift),
      binaryAnnotations)
  }

  def serviceNames: Set[String] = {
    annotations.flatMap(a => a.host.map(h => h.serviceName.toLowerCase)).toSet
  }

  /**
   * Tries to extract the best possible service name
   */
  def serviceName: Option[String] = {
    if (annotations.isEmpty) {
      None
    } else {
      val sName = serverSideAnnotations.flatMap(_.host).headOption.map(_.serviceName)
      val cName = clientSideAnnotations.flatMap(_.host).headOption.map(_.serviceName)
      sName match {
        case Some(s) => Some(s)
        case None => cName
      }
    }
  }

  /**
   * Iterate through list of annotations and return the one with the given value.
   */
  def getAnnotation(value: String): Option[Annotation] = {
    annotations.find {a => a.value == value}
  }

  /**
   * Take two spans with the same span id and merge all data into one of them.
   */
  def mergeSpan(mergeFrom: Span): Span = {
    if (id != mergeFrom.id) {
      throw new IllegalArgumentException("Span ids must match")
    }

    // ruby tracing can give us an empty name in one part of the span
    val selectedName = name match {
      case "" => mergeFrom.name
      case "Unknown" => mergeFrom.name
      case _ => name
    }

    new Span(traceId, selectedName, id, parentId,
      annotations ++ mergeFrom.annotations,
      binaryAnnotations ++ mergeFrom.binaryAnnotations)
  }

  /**
   * Get the first annotation by timestamp.
   */
  def firstAnnotation: Option[Annotation] = {
    try {
      Some(annotations.min(timestampOrdering))
    } catch {
      case e: UnsupportedOperationException => None
    }
  }

  /**
   * Get the last annotation by timestamp.
   */
  def lastAnnotation: Option[Annotation] = {
    try {
      Some(annotations.max(timestampOrdering))
    } catch {
      case e: UnsupportedOperationException => None
    }
  }

  /**
   * Endpoints involved in this span
   */
  def endpoints: Set[Endpoint] = {
    annotations.flatMap(a => a.host).toSet
  }

  /**
   * Assuming this is an RPC span, is it from the client side?
   */
  def isClientSide(): Boolean = {
    annotations.exists(a => {
      a.value.equals(gen.Constants.CLIENT_SEND) || a.value.equals(gen.Constants.CLIENT_RECV)
    })
  }

  /**
   * Pick out the core client side annotations
   */
  def clientSideAnnotations: Seq[Annotation] = {
    annotations.filter(a => Annotation.CoreClient.contains(a.value))
  }

  /**
   * Pick out the core server side annotations
   */
  def serverSideAnnotations: Seq[Annotation] = {
    annotations.filter(a => Annotation.CoreServer.contains(a.value))
  }

  /**
   * Duration of this span. May be None if we cannot find any annotations.
   */
  def duration: Option[Long] = {
    for (first <- firstAnnotation; last <- lastAnnotation)
      yield last.timestamp - first.timestamp
  }

  /**
   * @return true  if Span contains at most one of each core annotation
   *         false otherwise
   */
  def isValid: Boolean = {
    Annotation.CoreAnnotations.map { c =>
      annotations.filter { _.value == c }.length > 1
    }.count {b => b} == 0
  }

  /**
   * Get the annotations as a map with value to annotation bindings.
   */
  def getAnnotationsAsMap(): Map[String, Annotation] = annotations.map{ a => a.value -> a}.toMap
}
