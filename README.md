# minecraft-server-hibernator
Shuts down your Paper server while not in use.

**Use case:** home-hosted server which isn't frequently used. By shutting down the server while not in use, resources (both processor and memory) are saved.

**Functionality:** a Velocity plugin will start the server when a player attempts to join. After the server has been empty for a set time period, a Paper plugin will shut it down again. The Velocity and Paper plugin communicate using a HTTP server hosted by the Velocity plugin, to keep the Velocity plugin updated on whether the Paper server is online.

There is also the option of defining a limbo server, which will hold the players attempting to join while the server is starting up. Personally I use [NanoLimbo](https://github.com/Nan1t/NanoLimbo) for this task (which uses very little CPU and only ~80mb ram), but the server implementation does not matter.
