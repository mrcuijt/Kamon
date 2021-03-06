package kamon

import com.typesafe.config.Config
import kamon.context.BinaryPropagation.{ByteStreamReader, ByteStreamWriter}
import kamon.context.HttpPropagation.{HeaderReader, HeaderWriter}
import kamon.context.{BinaryPropagation, HttpPropagation, Propagation}

/**
  * Exposes APIs for Context propagation across HTTP and Binary transports.
  */
trait ContextPropagation { self: Configuration with ClassLoading =>
  @volatile private var _propagationComponents: ContextPropagation.Channels = _
  @volatile private var _defaultHttpPropagation: Propagation[HeaderReader, HeaderWriter] = _
  @volatile private var _defaultBinaryPropagation: Propagation[ByteStreamReader, ByteStreamWriter] = _

  // Initial configuration and reconfigures
  init(self.config)
  self.onReconfigure(newConfig => self.init(newConfig))


  /**
    * Retrieves the HTTP propagation channel with the supplied name. Propagation channels are configured on the
    * kamon.propagation.http configuration section.
    */
  def httpPropagation(channelName: String): Option[Propagation[HeaderReader, HeaderWriter]] =
    _propagationComponents.httpChannels.get(channelName)

  /**
    * Retrieves the binary propagation channel with the supplied name. Propagation channels are configured on the
    * kamon.propagation.binary configuration section.
    */
  def binaryPropagation(channelName: String): Option[Propagation[ByteStreamReader, ByteStreamWriter]] =
    _propagationComponents.binaryChannels.get(channelName)

  /**
    * Retrieves the default HTTP propagation channel. Configuration for this channel can be found under the
    * kamon.propagation.http.default configuration section.
    */
  def defaultHttpPropagation(): Propagation[HeaderReader, HeaderWriter] =
    _defaultHttpPropagation

  /**
    * Retrieves the default binary propagation channel. Configuration for this channel can be found under the
    * kamon.propagation.binary.default configuration section.
    */
  def defaultBinaryPropagation(): Propagation[ByteStreamReader, ByteStreamWriter] =
    _defaultBinaryPropagation

  private def init(config: Config): Unit = synchronized {
    _propagationComponents = ContextPropagation.Channels.from(self.config, self)
    _defaultHttpPropagation = _propagationComponents.httpChannels(ContextPropagation.DefaultHttpChannel)
    _defaultBinaryPropagation = _propagationComponents.binaryChannels(ContextPropagation.DefaultBinaryChannel)
  }
}

object ContextPropagation {

  val DefaultHttpChannel = "default"
  val DefaultBinaryChannel = "default"

  case class Channels(
    httpChannels: Map[String, Propagation[HeaderReader, HeaderWriter]],
    binaryChannels: Map[String, Propagation[ByteStreamReader, ByteStreamWriter]]
  )

  object Channels {

    /**
      * Creates a Channels instance from the provided configuration. The configuration details can be found on the
      * "kamon.propagation" section of the reference configuration.
      */
    def from(config: Config, classLoading: ClassLoading): Channels = {
      val propagationConfig = config.getConfig("kamon.propagation")
      val httpChannelsConfig = propagationConfig.getConfig("http").configurations
      val binaryChannelsConfig = propagationConfig.getConfig("binary").configurations

      val httpChannels = httpChannelsConfig.map {
        case (channelName, channelConfig) => (channelName -> HttpPropagation.from(channelConfig, classLoading))
      }

      val binaryChannels = binaryChannelsConfig.map {
        case (channelName, channelConfig) => (channelName -> BinaryPropagation.from(channelConfig, classLoading))
      }

      Channels(httpChannels, binaryChannels)
    }
  }
}
