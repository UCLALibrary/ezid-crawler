# ezid-crawler

A simple command line utility script for associating image files with ARKs.

### To Use:

Clone the repository and build the project:

    git clone https://github.com/UCLALibrary/ezid-crawler.git
    cd ezid-crawler
    mvn clean package

Once it's built there will be a Jar file created in the `target` directory with "exec" in its name. That is an executable Jar file that can be copied to the machine of your choice and run with a few additional parameters, for instance:

    java -jar ezid-crawler-0.0.1-SNAPSHOT-exec.jar -d /mnt/sinai/Arabic_NF_8/Data -o ./output.csv -e tiff,tif

Options:
* -d The local directory that contains the files to crawl and associate with ARKs
* -o The location of the output CSV file (which will contain ARKs and file paths)
* -e The comma-delimited list of file extensions of the files to include in the crawl

If you don't include an EZID username, password, and ARK shoulder, you will be asked for them at the time you run the Jar file.  Alternatively, you can pass them in as environmental properties from the command line:

    java -Dezid.username="apitest" -Dezid.password="apitest" -Dark.shoulder="ark:/99999/fk4" -jar ezid-crawler-0.0.1-SNAPSHOT-exec.jar -d /mnt/sinai/Arabic_NF_8/Data -o ./output.csv -e tiff,tif

That's it!

### Questions

Contact <a href="mailto:ksclarke@library.ucla.edu">Kevin S. Clarke</a>
