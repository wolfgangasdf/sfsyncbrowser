
# Introduction

SSyncBrowser is a file synchronization and remote file browsing program. It supports sftp and local mounts.
It is by far not as complete as cyberduck or winsftp, but with solid synchronization.

* Browse a server, view and edit files (like cyberduck/winscp). File synchronization happens automatically, folder not.
* Any folder can be synchronized quickly for data analysis etc. Very useful for offline work.
* It can start a ssh tunnel automatically.
* Quicklook on mac

* You can also define "permanent syncs" for backup and syncing
  * works also well with Android devices via ssh also without root (SSHelper etc) where the file attributes (time etc) sometimes can't be set.
  * It keeps a local database to keep track of changes (remote / local) without the need for full syncs.
  * You can define subsets for faster operation, while the same cache database is used.


### More information

* sftp: use publickey or password-based authentification (password stored in settings file)
* Symbolic link (symlink) handling: browser follows symlinks, synchronization ignores symlinks (but accepts symlinked paths in basefolders etc).


### Status ###
Works currently only on Mac/Linux!

File synchronization is a delicate thing. However, if you keep the Files list-view on the default setting "changes",
you can review everything before pressing `Synchronize`.

* There is no sanity check before synchronization, so you can create the paradox to delete a folder but copy a child file.
This will result in nice synchronization errors, no data loss will happen.
* The routine that assigns the initial actions after file scan is tested on program startup. Check the code, I find this is safe.
* But I can't be responsible for any data loss, of course.


### How to use ###

* Get the latest [Java JRE 8](http://www.oracle.com/technetwork/java/javase/downloads/index.html). Don't forget to untick the [crapware](https://www.google.com/search?q=java+crapware) installer, and/or [disable it permanently](https://www.java.com/en/download/faq/disable_offers.xml)!
* [Download the zip](https://github.com/wolfgangasdf/ssyncbrowser-test/releases) for Mac or (Windows, Linux), extract it somewhere and double-click the app (Mac) or
  jar file (Windows, Linux).

Everything should be self-explanatory (watch out for tooltips).


### How to develop, compile & package ###

* Get Oracle JDK 8
* I use the free community version of [IntelliJ IDEA](https://www.jetbrains.com/idea/download/), just import the project to get started.
* Package for all platforms: `gradle dist`.


### Used frameworks ###

* [Kotlin](https://kotlinlang.org/) and [Gradle](https://gradle.org/)
* [TornadoFX](https://github.com/edvin/tornadofx)
* [Shadow](https://github.com/johnrengelman/shadow) to package
* [SSHJ](https://github.com/hierynomus/sshj)
* [Directory-watcher](https://github.com/gmethvin/directory-watcher) to watch local files for changes

### License ###
[MIT](http://opensource.org/licenses/MIT)