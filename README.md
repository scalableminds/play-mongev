## Mongo evolution plugin for Play 2.2

based on the sql evolution plugin from Typesafe Inc.

This plugin helps to run evolutions on mongodb databases. It uses mongo scripts
written in javascript to migrate existing data to a new format. The plugin 
executes the scripts using the mongo console. It works the same way as the 
integrated sql evolutions do.

The evolution scripts need to be placed into `conf/evolutions/XXX.js`, where `XXX` denotes the evolution number (must be a valid integer). Evolutions will be executed in ascending order.

## Instalation

The package is published to sonatype and should get synced to maven central.

You need to add the package as a dependency:

```
libraryDependencies += "com.scalableminds" %% "play-mongev" % "0.2.5"
```

After adding the dependency, you need to load the plugin by adding this line to 
your `conf/play.plugins` file:

```
100:com.scalableminds.mongev.MongevPlugin
```

Make sure the plugin gets loaded before your mongo db driver.

## Configuration

<table>
  <tr>
    <th>Key</th>
    <th></th>
    <th>Description</th>
  </tr>
  <tr>
    <td><code>mongodb.evolution.enabled</code></td>
    <td>Boolean, optional, defaults to <code>false</code></td>
    <td>set to <code>true</code> to enable mongodb evolutions</td>
  </tr>

  <tr>
    <td><code>mongodb.evolution.mongoCmd</code></td>
    <td>String, <strong>required if enabled</strong></td>
    <td>cmd used to call mongo from cmd, eg. <code>mongo localhost:27017/test</code>. You need to include the server and db name. </td>
  </tr>

  <tr>
    <td><code>mongodb.evolution.applyProdEvolutions</code></td>
    <td>Boolean, optional, defaults to <code>false</code></td>
    <td>optionally enable/disable evolutions in production mode (enables only up evolutions)</td>
  </tr>

  <tr>
    <td><code>mongodb.evolution.applyDownEvolutions</code></td>
    <td>Boolean, optional, defaults to <code>false</code></td>
    <td>optionally enable/disable down evolutions in production mode</td>
  </tr>
</table>
