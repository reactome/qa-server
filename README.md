Weekly QA checks
================
The `src/main/scripts/qa-check-weekly.sh` bash script runs the pre-release QA checks. This is intended to run in background on a weekly basis. The run process is as follows:

1. Take a `gk_central` slice.

2. Run the Curator QA checks on the slice database.

3. Run the Release QA checks on the slice database.

4. Notify curators of relevant reports.

5. Prune all but the current and previous reports and slice databases.

Build
-----
The deployment target directory is `/usr/local/share/reactome/qa/weekly/`.
The content of this directory is shown in [Appendix A](#file-structure).
This content is built as follows:

1. Make a staging area, e.g. `$HOME/reactome/qa/weekly`.

2. Copy the `release-qa` project `src/main/scripts` files to the
   staging area `bin` subdirectory.

3. Export `CuratorTool` as a runnable jar file with main class
   `org.gk.slicing.SlicingEngine` and VM arugments `-Xmx8G` into
   the staging file `lib/SlicingTool-jar-with-dependencies.jar`.

4. Export `CuratorTool` as a runnable jar file with main class
   `org.gk.qualityCheck.CommandLineRunner` into the staging file `lib/CuratorQA-jar-with-dependencies.jar`.

5. Build the `release-qa` Maven package and copy the resulting
   `target` jar with dependencies to the staging file
   `lib/ReleaseQA-jar-with-dependencies.jar`. Do not use Eclipse
   to export this runnable jar with embedded dependency jars, since
   executing that runnable jar quietly exits immediately with no
   output.

6. Build the `qa-server` Maven package and copy the resulting
   `target` jar with dependencies to the staging file
   `lib/Notify-jar-with-dependencies.jar`.

7. Copy the relevant configuration files into the staging area.
   The configurations are documented in the respective projects,
   e.g. the `Notify/resources` files are described in `qa-release`
   `src/main/java/org/reactome/release/qa/Notify.java`.

   *Note*: the configuration settings are for the deployment
   server. Exercise caution that local workstation settings
   are not included in the staging area, since they will
   override the deployment settings.

Deploy
------
The deployment target is the Reactome curator server. Note that
the configuration settings should be for that server.

1. Bundle the staging content into a compressed tar file, e.g.:
   ````
   (cd $HOME/reactome/qa/weekly; tar cz *) > qa.tar.gz
   ````

2. Transfer the staging bundle to the deployment server.

3. On the deployment server, extract the staging content into
   `/usr/local/share/reactome/qa/weekly`.

4. Create the following cron job using `crontab -e`:

       0 0 * * 1 (cd /usr/local/share/reactome/qa/weekly; ./bin/qa-check-weekly.sh >run.out 2>run.err)

   This entry specifies that the weekly QA checks will run
   midnight Sunday on the deployment server. The script
   is run in the `/usr/local/share/reactome/qa/weekly`
   directory. Error output is redirected to `run.err`.
   Other output is redirected to `run.out`.

Appendix A: File structure
--------------------------
<a name="file-structure"></a>
````
 /usr/local/share/reactome/
   bin/
     qa-check-weekly.sh
     prune.sh
   lib/
     CuratorQA-jar-with-dependencies.jar
     SlicingTool-jar-with-dependencies.jar
     ReleaseQA-jar-with-dependencies.jar
     Notify-jar-with-dependencies.jar
   QAReports/
     # the generated QA check reports, e.g.:
     20180912/
       CuratorQA/
         ... # gk_central QA reports
       ReleaseQA/
         ... # Release QA reports
   CuratorQA/
     QA_SkipList/
       ... # skip lists
     resources/
       auth.properties
       log4j.properties
       ... # other Curator QA config files
   SlicingTool/
     slicingTool.prop
     SliceLog4j.properties
     Species.txt
     topics.txt
   ReleaseQA/
     resources/
       auth.properties
       log4j2.properties
       ... # other Release QA config files
   Notify/
     resources/
       curators.csv
       mail.properties
       log4j2.properties
````
