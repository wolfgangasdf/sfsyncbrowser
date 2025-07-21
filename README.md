
# Introduction

SFSyncBrowser is a file synchronization and remote file browsing program. It supports sftp and local mounts.
It is by far not as complete as cyberduck or winsftp, but it is fast (and not much code), with solid synchronization,
supports multiple bookmarks per server, and multiple protocols per server (like sftp/local mount).

* Browse a server, view and edit files. Use drag'n'drop between local fild browser and remote.
* Single-file synchronization happens automatically.
* Any remote folder can be synchronized quickly into a cache folder, very useful for offline work.
* It can start a ssh tunnel automatically if the host is not reachable directly.

* Define "permanent syncs" for backup and synchronization
  * Works also well with Android devices via ssh also without root (SSHelper etc) where the file attributes (time etc) sometimes can't be set.
  * It keeps a local database to keep track of changes (remote / local) without the need for full syncs.
  * You can define subsets for partial syncs (faster); the same cache database is used.

* SFSB does not use a proper disk-backed database which would allow an unlimited number of files, databases are slower than java's ConcurrentSkipListMap.
  1 GB RAM is enough for around a million files.

### More information

* sftp: use publickey or password-based authentication (password stored in settings file)
* Symbolic link (symlink) handling: browser follows symlinks, synchronization ignores symlinks (but accepts symlinked paths in basefolders etc).


### Status ###
I use it on mac daily, and less often on windows.

File synchronization is a delicate thing. However, if you keep the Files list-view on the default setting "changes",
you can review everything before pressing `Synchronize`.

* There is no sanity check before synchronization, so you can create the paradox to delete a folder but copy a child file.
This will result in nice synchronization errors, no data loss will happen.
* The routine that assigns the initial actions after file scan is tested on program startup. Check the code, I find this safe.
* But I can't be responsible for any data loss, of course.


### How to use ###

* [Download the zip](https://github.com/wolfgangasdf/sfsyncbrowser/releases), extract it somewhere and run it. It is not signed, google for "open unsigned mac/win".
* Everything should be self-explanatory (watch out for tooltips).
* Remote file browser keyboard shortcuts not in context menu:

    * right: enter folder
    * left: go to parent
    * space: quicklook (on mac)
    * meta-w: close
    * alphanumeric keys search through file list

### How to develop, compile & package ###

* Get Java from https://jdk.java.net
* Clone the repository
* I use the free community version of [IntelliJ IDEA](https://www.jetbrains.com/idea/download/) 
* Package for all platforms: `./gradlew clean dist`.

### Used frameworks ###

* [Kotlin](https://kotlinlang.org/) and [Gradle](https://gradle.org/)
* [TornadoFX2](https://github.com/edvin/tornadofx2)
* [SSHJ](https://github.com/hierynomus/sshj)
* [Directory-watcher](https://github.com/gmethvin/directory-watcher) to watch local files for changes
* [Beryx runtime](https://github.com/beryx/badass-runtime-plugin) to make runtimes with JRE

### License ###
[MIT](http://opensource.org/licenses/MIT)