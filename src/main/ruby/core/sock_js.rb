# Copyright 2011 the original author or authors.
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#      http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

require 'core/streams'
require 'core/ssl_support'
require 'rubygems'
require 'json'

module Vertx

  # This is an implementation of the server side part of {"https://github.com/sockjs"}
  #
  # SockJS enables browsers to communicate with the server using a simple WebSocket-like api for sending
  # and receiving messages. Under the bonnet SockJS chooses to use one of several protocols depending on browser
  # capabilities and what apppears to be working across the network.
  #
  # Available protocols include:
  #
  # WebSockets
  # xhr-polling
  # xhr-streaming
  # json-polling
  # event-source
  # html-file
  #
  # This means, it should just work irrespective of what browser is being used, and whether there are nasty
  # things like proxies and load balancers between the client and the server.
  #
  # For more detailed information on SockJS, see their website.
  #
  # On the server side, you interact using instances of {SockJSSocket} - this allows you to send data to the
  # client or receive data via the {ReadStream#data_handler}.
  #
  # You can register multiple applications with the same SockJSServer, each using different path prefixes, each
  # application will have its own handler, and configuration is described in a Hash.
  #
  # @author {http://tfox.org Tim Fox}
  class SockJSServer

    # Create a new SockJSServer
    # @param http_server [HttpServer] You must pass in an instance of {HttpServer}
    def initialize(http_server)
      @j_server = org.vertx.java.core.sockjs.SockJSServer.new(http_server._to_java_server)
    end

    # Install an application
    # @param config [Hash] Configuration for the application
    # @param proc [Proc] Proc representing the handler
    # @param hndlr [Block] Handler to call when a new {SockJSSocket is created}
    def install_app(config, proc = nil, &hndlr)
      hndlr = proc if proc

      j_config = org.vertx.java.core.sockjs.AppConfig.new

      prefix = config["prefix"]
      j_config.setPrefix(prefix) if prefix
      jsessionid = config["insert_JSESSIONID"]
      j_config.setInsertJSESSIONID(jsessionid) if jsessionid
      session_timeout = config["session_timeout"]
      j_config.setSessionTimeout(session_timeout) if session_timeout
      heartbeat_period = config["heartbeat_period"]
      j_config.setHeartbeatPeriod(heartbeat_period) if heartbeat_period
      max_bytes_streaming = config["max_bytes_streaming"]
      j_config.setMaxBytesStreaming(max_bytes_streaming) if max_bytes_streaming
      library_url = config["library_url"]
      j_config.setLibraryURL(library_url) if library_url

      # TODO disabled transports

      @j_server.installApp(j_config) { |j_sock|
        hndlr.call(SockJSSocket.new(j_sock))
      }
    end

  end

  # You interact with SockJS clients through instances of SockJS socket.
  # The API is very similar to {Websocket}. It implements both
  # {ReadStream} and {WriteStream} so it can be used with {Pump} to enable
  # flow control.
  #
  # @author {http://tfox.org Tim Fox}
  class SockJSSocket

    include ReadStream, WriteStream

    # @private
    def initialize(j_sock)
      @j_del = j_sock
      @handler_id = Vertx::register_handler { |buffer|
        write_buffer(buffer)
      }
    end

    # Close the socket
    def close
      @j_del.close
    end

    # When a SockJSSocket is created it automatically registers an event handler with the system, the ID of that
    # handler is given by {#handler_id}.
    # Given this ID, a different event loop can send a buffer to that event handler using {Vertx.send_to_handler} and
    # that buffer will be received by this instance in its own event loop and writing to the underlying connection. This
    # allows you to write data to other SockJSSockets which are owned by different event loops.
    def handler_id
      @handler_id
    end

    # @private
    def _to_java_socket
      @j_del
    end

  end

  # A SockJSBridgeHandler plugs into a SockJS server and translates data received via SockJS into operations
  # to send messages and register and unregister handlers on the vert.x event bus.
  #
  # When used in conjunction with the vert.x client side JavaScript event bus api (vertxbus.js) this effectively
  # extends the reach of the vert.x event bus from vert.x server side applications to the browser as well. This
  # enables a truly transparent single event bus where client side JavaScript applications can play on the same
  # bus as server side application instances and services.
  #
  # @author {http://tfox.org Tim Fox}
  class SockJSBridgeHandler < org.vertx.java.core.eventbus.SockJSBridgeHandler
    def initialize
      super
      @json_helper = org.vertx.java.core.eventbus.JsonHelper.new
    end

    # Call this handler - pretend to be a Proc
    def call(sock)
      # This is inefficient since we convert to a Ruby SockJSSocket and back again to a Java one
      handle(sock._to_java_socket)
    end

    def add_matches(*matches)
      matches.each do |match|
        json_str = JSON.generate(match);
        j_json = @json_helper.stringToJson(json_str);
        addMatch(j_json);
      end
    end

  end

end