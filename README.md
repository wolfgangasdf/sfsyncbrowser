
# Introduction

SSyncBrowser is a file synchronization and remote file browsing program which works via sftp, scp etc.

* Browse a server, view and edit files (like cyberduck/winscp)
* Any folder can be synchronized quickly for data analysis etc. Also useful for offline work. You will be reminded to delete the local copy before close (avoid mess).
* It can switch on a ssh tunnel automatically.

* You can also define "permanent syncs" for backup and syncing
  * works also with a very large amount of files (tested with several millions)
  * works also well with Android devices via ssh also without root (SSHelper etc) where the file attributes (time etc) can't be set.
  * It keeps a local database to keep track of changes (remote / local) without needing full syncs.
  * You can define subsets of sync'ed folders for faster operation, while the same cache file is used.

## Features

* Quicklook on mac
* Automatic ssh tunnel
* Symbolic link (symlink) handling: browser follows directory links, synchronization ignores symlinks (but accepts symlinked paths in basefolders etc).

### More information

* sftp: use publickey or password-based authentification (password stored in settings, hashed but not very secure)
* sftp: remote symbolic links (symlinks) are not 'followed'

### Status ###
File synchronization is a delicate thing. However, if you keep the Files list-view on the default setting "changes",
you can review everything before pressing `Synchronize`.

* I use it since years without any data loss
* There is no sanity check before synchronization, so you can create the paradox to delete a folder but copy a child file.
This will result in nice synchronization errors, but no data loss will happen.
* The routine that assigns the initial actions after file scan is tested on program startup. Check the code, I find this is safe.
* But I can't be responsible for any data loss, of course.

### How to use ###

* Get the [Java JRE](http://www.oracle.com/technetwork/java/javase/downloads/index.html) >= 8u101. Don't forget to untick the [crapware](https://www.google.com/search?q=java+crapware) installer, and/or [disable it permanently](https://www.java.com/en/download/faq/disable_offers.xml)!
* [Download the zip](https://bitbucket.org/wolfgang/sfsync/downloads) for Mac or (Windows, Linux), extract it somewhere and double-click the app (Mac) or
  jar file (Windows, Linux).

Everything should be self-explanatory (watch out for tooltips).

### How to develop, compile & package ###

* Get Java JDK >= 8u101
* check out the code
* I use the free community version of [IntelliJ IDEA](https://www.jetbrains.com/idea/download/), just import the project to get started.

Run Reftool from terminal and package it:

* Install the [Scala Build Tool](http://www.scala-sbt.org/)
* Compile and run manually: `gradle`
* Package for all platforms: `gradle dist`.


### Used frameworks ###

* [Kotlin](https://kotlinlang.org/) and [Gradle](https://gradle.org/)
* [TornadoFX](https://github.com/edvin/tornadofx) for javafx gui
* [Shadow](https://github.com/johnrengelman/shadow) to package
* [SSHJ](https://github.com/hierynomus/sshj) to synchronize via sftp

### License ###
[MIT](http://opensource.org/licenses/MIT)