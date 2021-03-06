/* =========================================================================================
 * Copyright © 2013-2017 the kamon project <http://kamon.io/>
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file
 * except in compliance with the License. You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
 * either express or implied. See the License for the specific language governing permissions
 * and limitations under the License.
 * =========================================================================================
 */

package kamon
package trace

import java.time.Instant
import java.util.concurrent.{ScheduledExecutorService, ScheduledFuture, TimeUnit}

import com.typesafe.config.Config
import kamon.context.Context
import kamon.tag.TagSet
import kamon.tag.Lookups.option
import kamon.trace.Span.{Kind, Position, TagKeys}
import kamon.trace.Trace.SamplingDecision
import kamon.util.Clock
import org.jctools.queues.{MessagePassingQueue, MpscArrayQueue}
import org.slf4j.LoggerFactory

import scala.collection.JavaConverters.collectionAsScalaIterableConverter
import scala.util.Try


/**
  * A Tracer assists on the creation of Spans and temporarily holds finished Spans until they are flushed to the
  * available reporters.
  */
class Tracer(initialConfig: Config, clock: Clock, classLoading: ClassLoading, contextStorage: ContextStorage,
    scheduler: ScheduledExecutorService) {

  import Tracer._logger

  @volatile private var _traceReporterQueueSize = 4096
  @volatile private var _spanBuffer = new MpscArrayQueue[Span.Finished](_traceReporterQueueSize)
  @volatile private var _joinRemoteParentsWithSameSpanID: Boolean = false
  @volatile private var _includeErrorStacktrace: Boolean = true
  @volatile private var _tagWithInitiatorService: Boolean = true
  @volatile private var _tagWithParentOperation: Boolean = true
  @volatile private var _sampler: Sampler = ConstantSampler.Never
  @volatile private var _identifierScheme: Identifier.Scheme = Identifier.Scheme.Single
  @volatile private var _adaptiveSamplerSchedule: Option[ScheduledFuture[_]] = None
  @volatile private var _preStartHooks: Array[Tracer.PreStartHook] = Array.empty
  @volatile private var _preFinishHooks: Array[Tracer.PreFinishHook] = Array.empty
  private val _onSpanFinish: Span.Finished => Unit = _spanBuffer.offer

  reconfigure(initialConfig)


  /**
    * Returns the Identifier Scheme currently used by the tracer.
    */
  def identifierScheme: Identifier.Scheme =
    _identifierScheme


  /**
    * Creates a new SpanBuilder for a Server Span and applies the provided component name as a metric tag. It is
    * recommended that all Spans include a "component" metric tag that indicates what library or library section is
    * generating the Span.
    */
  def serverSpanBuilder(operationName: String, component: String): SpanBuilder =
    spanBuilder(operationName).kind(Kind.Server).tagMetric(Span.TagKeys.Component, component)


  /**
    * Creates a new SpanBuilder for a Client Span and applies the provided component name as a metric tag. It is
    * recommended that all Spans include a "component" metric tag that indicates what library or library section is
    * generating the Span.
    */
  def clientSpanBuilder(operationName: String, component: String): SpanBuilder =
    spanBuilder(operationName).kind(Kind.Client).tagMetric(Span.TagKeys.Component, component)


  /**
    * Creates a new SpanBuilder for a Producer Span and applies the provided component name as a metric tag. It is
    * recommended that all Spans include a "component" metric tag that indicates what library or library section is
    * generating the Span.
    */
  def producerSpanBuilder(operationName: String, component: String): SpanBuilder =
    spanBuilder(operationName).kind(Kind.Producer).tagMetric(Span.TagKeys.Component, component)


  /**
    * Creates a new SpanBuilder for a Consumer Span and applies the provided component name as a metric tag. It is
    * recommended that all Spans include a "component" metric tag that indicates what library or library section is
    * generating the Span.
    */
  def consumerSpanBuilder(operationName: String, component: String): SpanBuilder =
    spanBuilder(operationName).kind(Kind.Consumer).tagMetric(Span.TagKeys.Component, component)


  /**
    * Creates a new SpanBuilder for an Internal Span and applies the provided component name as a metric tag. It is
    * recommended that all Spans include a "component" metric tag that indicates what library or library section is
    * generating the Span.
    */
  def internalSpanBuilder(operationName: String, component: String): SpanBuilder =
    spanBuilder(operationName).kind(Kind.Internal).tagMetric(Span.TagKeys.Component, component)


  /**
    * Creates a new raw SpanBuilder instance using the provided operation name.
    */
  def spanBuilder(initialOperationName: String): SpanBuilder =
    new MutableSpanBuilder(initialOperationName)

  /**
    * Removes and returns all finished Spans currently held by the tracer.
    */
  def spans(): Seq[Span.Finished] = {
    var spans = Seq.empty[Span.Finished]
    _spanBuffer.drain(new MessagePassingQueue.Consumer[Span.Finished] {
      override def accept(span: Span.Finished): Unit =
        spans = span +: spans
    })

    spans
  }

  private class MutableSpanBuilder(initialOperationName: String) extends SpanBuilder {
    private val _spanTags = TagSet.builder()
    private val _metricTags = TagSet.builder()

    private var _trackMetrics = true
    private var _name = initialOperationName
    private var _marks: Seq[Span.Mark] = Seq.empty
    private var _errorMessage: String = _
    private var _errorCause: Throwable = _
    private var _ignoreParentFromContext = false
    private var _parent: Option[Span] = None
    private var _context: Option[Context] = None
    private var _suggestedTraceId: Identifier = Identifier.Empty
    private var _kind: Span.Kind = Span.Kind.Unknown

    override def operationName(): String = _name

    override def tags(): TagSet = _spanTags.create()

    override def metricTags(): TagSet = _metricTags.create()

    override def name(name: String): SpanBuilder = {
      _name = name
      this
    }

    override def tag(key: String, value: String): SpanBuilder = {
      _spanTags.add(key, value)
      this
    }

    override def tag(key: String, value: Long): SpanBuilder = {
      _spanTags.add(key, value)
      this
    }

    override def tag(key: String, value: Boolean): SpanBuilder = {
      _spanTags.add(key, value)
      this
    }

    override def tagMetric(key: String, value: String): SpanBuilder = {
      _metricTags.add(key, value)
      this
    }

    override def tagMetric(key: String, value: Long): SpanBuilder = {
      _metricTags.add(key, value)
      this
    }

    override def tagMetric(key: String, value: Boolean): SpanBuilder = {
      _metricTags.add(key, value)
      this
    }

    override def mark(key: String): SpanBuilder = {
      _marks = Span.Mark(clock.instant(), key) +: _marks
      this
    }

    override def mark(at: Instant, key: String): SpanBuilder = {
      _marks = Span.Mark(at, key) +: _marks
      this
    }

    override def fail(errorMessage: String): SpanBuilder = {
      _errorMessage = errorMessage
      this
    }

    override def fail(cause: Throwable): SpanBuilder = {
      _errorCause = cause
      this
    }

    override def fail(errorMessage: String, cause: Throwable): SpanBuilder = {
      fail(errorMessage); fail(cause)
      this
    }

    override def enableMetrics(): SpanBuilder = {
      _trackMetrics = true
      this
    }

    override def disableMetrics(): SpanBuilder = {
      _trackMetrics = false
      this
    }

    override def ignoreParentFromContext(): SpanBuilder = {
      _ignoreParentFromContext = true
      this
    }

    override def asChildOf(parent: Span): SpanBuilder = {
      _parent = Option(parent)
      this
    }

    override def context(context: Context): SpanBuilder = {
      _context = Some(context)
      this
    }

    override def traceId(id: Identifier): SpanBuilder = {
      _suggestedTraceId = id
      this
    }

    override def kind(kind: Span.Kind): SpanBuilder = {
      _kind = kind
      this
    }

    override def start(): Span =
    start(clock.instant())

    /** Uses all the accumulated information to create a new Span */
    override def start(at: Instant): Span = {
      if(_preStartHooks.nonEmpty) {
        _preStartHooks.foreach(psh => {
          try {
            psh.beforeStart(this)
          } catch {
            case t: Throwable =>
              _logger.error("Failed to apply pre-start hook", t)
          }
        })
      }

      val context = _context.getOrElse(contextStorage.currentContext())
      if(_tagWithInitiatorService) {
        context.getTag(option(TagKeys.InitiatorName)).foreach(initiatorName => {
          _metricTags.add(TagKeys.InitiatorName, initiatorName)
        })
      }

      val parent = _parent.getOrElse(if (_ignoreParentFromContext) Span.Empty else context.get(Span.Key))
      val localParent = if (!parent.isRemote && !parent.isEmpty) Some(parent) else None

      val (id, parentId) =
        if (parent.isRemote && _kind == Span.Kind.Server && _joinRemoteParentsWithSameSpanID)
          (parent.id, parent.parentId)
        else
          (identifierScheme.spanIdFactory.generate(), parent.id)

      val traceId =
        if (parent.trace.id.isEmpty) {
          if (!_suggestedTraceId.isEmpty)
            _suggestedTraceId
          else
            identifierScheme.traceIdFactory.generate()
        } else parent.trace.id

      val position =
        if (parent.isEmpty)
          Position.Root
        else if (parent.isRemote)
          Position.LocalRoot
        else
          Position.Unknown

      val samplingDecision =
        if (position == Position.Root || parent.trace.samplingDecision == SamplingDecision.Unknown)
          _sampler.decide(this)
        else
          parent.trace.samplingDecision

      val trace = Trace(traceId, samplingDecision)

      new Span.Local(id, parentId, trace, position, _kind, localParent, _name, _spanTags, _metricTags, at, _trackMetrics,
        _tagWithParentOperation, _includeErrorStacktrace, clock, _preFinishHooks, _onSpanFinish)
    }
  }


  /**
    * Applies a new configuration to the tracer and its related components.
    */
  def reconfigure(newConfig: Config): Unit = synchronized {
    Try {
      val traceConfig = newConfig.getConfig("kamon.trace")
      val sampler = traceConfig.getString("sampler") match {
        case "always"     => ConstantSampler.Always
        case "never"      => ConstantSampler.Never
        case "random"     => RandomSampler(traceConfig.getDouble("random-sampler.probability"))
        case "adaptive"   => AdaptiveSampler()
        case fqcn         =>

          // We assume that any other value must be a FQCN of a Sampler implementation and try to build an
          // instance from it.
          val customSampler = classLoading.createInstance[Sampler](fqcn)
          customSampler.failed.foreach(t => {
            _logger.error(s"Failed to create sampler instance from FQCN [$fqcn], falling back to random sampling with 10% probability", t)
          })

          customSampler.getOrElse(RandomSampler(0.1D))
      }

      val identifierScheme = traceConfig.getString("identifier-scheme") match {
        case "single" => Identifier.Scheme.Single
        case "double" => Identifier.Scheme.Double
        case fqcn =>

          // We assume that any other value must be a FQCN of an Identifier Scheme implementation and try to build an
          // instance from it.
          val customSampler = classLoading.createInstance[Identifier.Scheme](fqcn)
          customSampler.failed.foreach(t => {
            _logger.error(s"Failed to create identifier scheme instance from FQCN [$fqcn], falling back to the single scheme", t)
          })

          customSampler.getOrElse(Identifier.Scheme.Single)
      }

      if(sampler.isInstanceOf[AdaptiveSampler]) {
        if(_adaptiveSamplerSchedule.isEmpty)
          _adaptiveSamplerSchedule = Some(scheduler.scheduleAtFixedRate(
            adaptiveSamplerAdaptRunnable(), 1, 1, TimeUnit.SECONDS
          ))
      } else {
        _adaptiveSamplerSchedule.foreach(_.cancel(false))
        _adaptiveSamplerSchedule = None
      }

      val preStartHooks = traceConfig.getStringList("hooks.pre-start").asScala
        .map(preStart => classLoading.createInstance[Tracer.PreStartHook](preStart).get).toArray

      val preFinishHooks = traceConfig.getStringList("hooks.pre-finish").asScala
        .map(preFinish => classLoading.createInstance[Tracer.PreFinishHook](preFinish).get).toArray

      val traceReporterQueueSize = traceConfig.getInt("reporter-queue-size")
      val joinRemoteParentsWithSameSpanID = traceConfig.getBoolean("join-remote-parents-with-same-span-id")
      val tagWithInitiatorService = traceConfig.getBoolean("span-metric-tags.initiator-service")
      val tagWithParentOperation = traceConfig.getBoolean("span-metric-tags.parent-operation")
      val includeErrorStacktrace = traceConfig.getBoolean("include-error-stacktrace")

      if(_traceReporterQueueSize != traceReporterQueueSize) {
        // By simply changing the buffer we might be dropping Spans that have not been collected yet by the reporters.
        // Since reconfigures are very unlikely to happen beyond application startup this might not be a problem.
        // If we eventually decide to keep those possible Spans around then we will need to change the queue type to
        // multiple consumer as the reconfiguring thread will need to drain the contents before replacing.
        _spanBuffer = new MpscArrayQueue[Span.Finished](traceReporterQueueSize)
      }

      _sampler = sampler
      _identifierScheme = identifierScheme
      _joinRemoteParentsWithSameSpanID = joinRemoteParentsWithSameSpanID
      _includeErrorStacktrace = includeErrorStacktrace
      _tagWithInitiatorService = tagWithInitiatorService
      _tagWithParentOperation = tagWithParentOperation
      _traceReporterQueueSize = traceReporterQueueSize
      _preStartHooks = preStartHooks
      _preFinishHooks = preFinishHooks

    }.failed.foreach {
      ex => _logger.error("Failed to reconfigure the Kamon tracer", ex)
    }
  }

  private def adaptiveSamplerAdaptRunnable(): Runnable = new Runnable {
    override def run(): Unit = {
      _sampler match {
        case adaptiveSampler: AdaptiveSampler => adaptiveSampler.adapt()
        case _ => // just ignore any other sampler type.
      }
    }
  }
}

object Tracer {

  private val _logger = LoggerFactory.getLogger(classOf[Tracer])

  /**
    * A callback function that is applied to all SpanBuilder instances right before they are turned into an actual
    * Span. PreStartHook implementations are configured using the "kamon.trace.hooks.pre-start" configuration setting
    * and all implementations must have a parameter-less constructor.
    */
  trait PreStartHook {
    def beforeStart(builder: SpanBuilder): Unit
  }


  /**
    * A callback function that is applied to all Span instances right before they are finished and flushed to Span
    * reporters. PreFinishHook implementations are configured using the "kamon.trace.hooks.pre-finish" configuration
    * setting and all implementations must have a parameter-less constructor.
    */
  trait PreFinishHook {
    def beforeFinish(span: Span): Unit
  }
}
