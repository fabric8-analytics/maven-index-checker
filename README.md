Maven Index Checker
======

`usage: maven-index-checker`
 
`-n,--new-only            only entries added to index since last run. Is set to false if -r/-range is used.`
 
`-r,--range <arg>         dash separated range in maven index. Indexes are taken from end (the newest), i.e. eg. 0-1000 actually means from last-1000 to last.`

`-mj,-max-jars            maximal number of jars to be printed.`

# Introduction
Maven Index Checker for [Anitya](https://github.com/fedora-infra/anitya).

Anitya supports various project backends. This project provides latest releases from [Maven Central](http://repo2.maven.apache.org/maven2/).

# Running

## Dependencies

You need just `java` and `maven` dependencies.

## Build

For first run you have to build first with `mvn clean package`. 
After this you can run with `java -jar target/maven-release-checker-1.0-SNAPSHOT.jar`. 
First run takes some time because it downloads and unpacks whole maven index.
After this it outputs latest 50 packages in form of JSON. Maven Central index is updated each week. 
