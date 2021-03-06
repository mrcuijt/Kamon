package kamon
package metric

import java.time.{Duration, Instant}
import java.util.concurrent.ScheduledExecutorService

import com.typesafe.config.Config
import kamon.metric.Metric.BaseMetric
import kamon.status.Status
import kamon.util.Clock
import org.slf4j.LoggerFactory

import scala.collection.concurrent.TrieMap

/**
  * Handles creation and snapshotting of metrics. If a metric is created twice, the very same instance will be returned.
  * If an attempt to create an existent metric with different settings is made, the new settings are ignored in favor of
  * those of the already registered metric.
  *
  */
class MetricRegistry(config: Config, scheduler: ScheduledExecutorService, clock: Clock) {
  private val _logger = LoggerFactory.getLogger(classOf[MetricRegistry])
  private val _metrics = TrieMap.empty[String, BaseMetric[_, _, _]]
  @volatile private var _lastSnapshotInstant: Instant = clock.instant()
  @volatile private var _factory: MetricFactory = MetricFactory.from(config, scheduler, clock)


  /**
    * Retrieves or registers a new counter-based metric.
    */
  def counter(name: String, description: Option[String], unit: Option[MeasurementUnit], autoUpdateInterval: Option[Duration]):
      Metric.Counter = {

    val metric = _metrics.atomicGetOrElseUpdate(name, _factory.counter(name, description, unit, autoUpdateInterval))
        .asInstanceOf[Metric.Counter]

    checkInstrumentType(name, Instrument.Type.Counter, metric)
    checkDescription(metric.name, metric.description, description)
    checkUnit(metric.name, metric.settings.unit, unit)
    checkAutoUpdate(metric.name, metric.settings.autoUpdateInterval, autoUpdateInterval)
    metric
  }

  /**
    * Retrieves or registers a new gauge-based metric.
    */
  def gauge(name: String, description: Option[String], unit: Option[MeasurementUnit], autoUpdateInterval: Option[Duration]):
  Metric.Gauge = {

    val metric = _metrics.atomicGetOrElseUpdate(name, _factory.gauge(name, description, unit, autoUpdateInterval))
      .asInstanceOf[Metric.Gauge]

    checkInstrumentType(name, Instrument.Type.Gauge, metric)
    checkDescription(metric.name, metric.description, description)
    checkUnit(metric.name, metric.settings.unit, unit)
    checkAutoUpdate(metric.name, metric.settings.autoUpdateInterval, autoUpdateInterval)
    metric
  }

  /**
    * Retrieves or registers a new histogram-based metric.
    */
  def histogram(name: String, description: Option[String], unit: Option[MeasurementUnit], dynamicRange: Option[DynamicRange],
    autoUpdateInterval: Option[Duration]): Metric.Histogram = {

    val metric = _metrics.atomicGetOrElseUpdate(name, _factory.histogram(name, description, unit, dynamicRange,
      autoUpdateInterval)).asInstanceOf[Metric.Histogram]

    checkInstrumentType(name, Instrument.Type.Histogram, metric)
    checkDescription(metric.name, metric.description, description)
    checkUnit(metric.name, metric.settings.unit, unit)
    checkDynamicRange(metric.name, metric.settings.dynamicRange, dynamicRange)
    checkAutoUpdate(metric.name, metric.settings.autoUpdateInterval, autoUpdateInterval)
    metric
  }

  /**
    * Retrieves or registers a new timer-based metric.
    */
  def timer(name: String, description: Option[String], dynamicRange: Option[DynamicRange], autoUpdateInterval: Option[Duration]): Metric.Timer = {

    val metric = _metrics.atomicGetOrElseUpdate(name, _factory.timer(name, description, Some(MeasurementUnit.time.nanoseconds),
      dynamicRange, autoUpdateInterval)).asInstanceOf[Metric.Timer]

    checkInstrumentType(name, Instrument.Type.Timer, metric)
    checkDescription(metric.name, metric.description, description)
    checkDynamicRange(metric.name, metric.settings.dynamicRange, dynamicRange)
    checkAutoUpdate(metric.name, metric.settings.autoUpdateInterval, autoUpdateInterval)
    metric
  }

  /**
    * Retrieves or registers a new range sampler-based metric.
    */
  def rangeSampler(name: String, description: Option[String], unit: Option[MeasurementUnit], dynamicRange: Option[DynamicRange],
    autoUpdateInterval: Option[Duration]): Metric.RangeSampler = {

    val metric = _metrics.atomicGetOrElseUpdate(name, _factory.rangeSampler(name, description, unit, dynamicRange,
      autoUpdateInterval)).asInstanceOf[Metric.RangeSampler]

    checkInstrumentType(name, Instrument.Type.RangeSampler, metric)
    checkDescription(metric.name, metric.description, description)
    checkUnit(metric.name, metric.settings.unit, unit)
    checkDynamicRange(metric.name, metric.settings.dynamicRange, dynamicRange)
    checkAutoUpdate(metric.name, metric.settings.autoUpdateInterval, autoUpdateInterval)
    metric
  }

  /**
    * Reconfigures the registry using the provided configuration.
    */
  def reconfigure(newConfig: Config): Unit = {
    _factory = MetricFactory.from(newConfig, scheduler, clock)
  }

  private def checkInstrumentType(name: String, instrumentType: Instrument.Type, metric: Metric[_, _]): Unit =
    if(!instrumentType.implementation.isInstance(metric))
      sys.error(s"Cannot redefine metric [$name] as a [${instrumentType.name}], it was already registered as a [${metric.getClass.getName}]")

  private def checkDescription(name: String, description: String, providedDescription: Option[String]): Unit =
    if(providedDescription.filter(d => d != description).nonEmpty)
      _logger.warn(s"Ignoring new description [${providedDescription.getOrElse("")}] for metric [${name}]")

  private def checkUnit(name: String, unit: MeasurementUnit, providedUnit: Option[MeasurementUnit]): Unit =
    if(providedUnit.filter(u => u != unit).nonEmpty)
      _logger.warn(s"Ignoring new unit [${providedUnit.getOrElse("")}] for metric [${name}]")

  private def checkAutoUpdate(name: String, autoUpdateInterval: Duration, providedAutoUpdateInterval: Option[Duration]): Unit =
    if(providedAutoUpdateInterval.filter(u => u != autoUpdateInterval).nonEmpty)
      _logger.warn(s"Ignoring new auto-update interval [${providedAutoUpdateInterval.getOrElse("")}] for metric [${name}]")

  private def checkDynamicRange(name: String, dynamicRange: DynamicRange, providedDynamicRange: Option[DynamicRange]): Unit =
    if(providedDynamicRange.filter(dr => dr != dynamicRange).nonEmpty)
      _logger.warn(s"Ignoring new dynamic range [${providedDynamicRange.getOrElse("")}] for metric [${name}]")



  /**
    * Creates a period snapshot of all metrics contained in this registry. The period always starts at the instant of
    * the last snapshot taken in which the state was reset and until the current instant. The special case of the first
    * snapshot uses the registry creation instant as the starting point.
    */
  def snapshot(resetState: Boolean): PeriodSnapshot = synchronized {
    val counters = Map.newBuilder[String, MetricSnapshot.Value[Long]]
    val gauges = Map.newBuilder[String, MetricSnapshot.Value[Double]]
    val histograms = Map.newBuilder[String, MetricSnapshot.Distribution]
    val timers = Map.newBuilder[String, MetricSnapshot.Distribution]
    val rangeSamplers = Map.newBuilder[String, MetricSnapshot.Distribution]

    _metrics.foreach {
      case (_, metric) => metric match {
        case m: Metric.Counter      => counters += m.name -> m.snapshot(resetState).asInstanceOf[MetricSnapshot.Value[Long]]
        case m: Metric.Gauge        => gauges += m.name -> m.snapshot(resetState).asInstanceOf[MetricSnapshot.Value[Double]]
        case m: Metric.Histogram    => histograms += m.name -> m.snapshot(resetState).asInstanceOf[MetricSnapshot.Distribution]
        case m: Metric.Timer        => timers += m.name -> m.snapshot(resetState).asInstanceOf[MetricSnapshot.Distribution]
        case m: Metric.RangeSampler => rangeSamplers += m.name -> m.snapshot(resetState).asInstanceOf[MetricSnapshot.Distribution]
      }
    }

    val periodStart = _lastSnapshotInstant
    val periodEnd = clock.instant()
    _lastSnapshotInstant = periodEnd

    PeriodSnapshot(periodStart, periodEnd, counters.result(), gauges.result(), histograms.result(), timers.result(), rangeSamplers.result())
  }

  /** Returns the current status of all metrics contained in the registry */
  def status(): Status.MetricRegistry =
    Status.MetricRegistry(_metrics.values.map(_.status()).toSeq)
}
