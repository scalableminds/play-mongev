## Mongo evolution plugin for Play 2.2

## Instalation

The package is not yet published. I am waiting for sonatype to accept my 
publishing request.

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
