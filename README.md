# minecraft-server-hibernator
Shuts down your Paper server while not in use.

**Use case:** home-hosted server which isn't frequently used. By shutting down the server while not in use, resources (both processor and memory) are saved.

**Functionality:** a Velocity plugin will start the server when a player attempts to join. After a set time period, a Paper plugin will shut down the server again. The Velocity and Paper plugin communicate using a HTTP server hosted by the Velocity plugin, to keep the Velocity plugin updated on whether the Paper server is online.