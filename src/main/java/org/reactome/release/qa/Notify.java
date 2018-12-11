package org.reactome.release.qa;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Properties;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import javax.mail.Address;
import javax.mail.Message;
import javax.mail.Session;
import javax.mail.Transport;
import javax.mail.internet.InternetAddress;
import javax.mail.internet.MimeMessage;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.opencsv.CSVParser;
import com.opencsv.CSVParserBuilder;
import com.opencsv.CSVReader;
import com.opencsv.CSVReaderBuilder;
import com.opencsv.CSVReaderHeaderAware;
import com.opencsv.CSVReaderHeaderAwareBuilder;

/**
 * Notifies the responsible curators of automated QA reports.
 * 
 * Note: notification requires two configuration files in the working directory:
 * <ul>
 * <li><code>curators.csv</code> - the list of potential curators</li>
 * <li><code>mail.properties</code> - the JavaMail properties</li>
 * </ul>
 * <p>
 * <code>curators.csv</code> has columns Coordinator, Surname, First Name
 * and Email. <em>Coordinator</em> is the release coordinator flag. Each
 * coordinator is notified of all QA reports. Non-coordinators are notified
 * of only those reports where they are listed as the last modifier.
 * </p>
 * <p>
 * <code>mail.properties</code> is the JavaMail properties. The following
 * properties are recommended:
 * <ul>
 * <li><code>mail.from</code> - the required mail sender
 * <li><code>mail.smtp.host</code> - the optional mail host name
 *     (default <code>localhost</code>)
 * </li>
 * <li><code>mail.smtp.port</code> - the optional mail port (default 25)</li>
 * </ul>
 * </li>
 * </ul>
 * </p>
 * 
 * @author Fred Loney <loneyf@ohsu.edu>
 */
public class Notify {

    private static final String SUMMARY_TITLE = "QA Report Summary";

    private static final String SUMMARY_NOTIFICATION_FILE_NM = "summary.html";

    // TODO - on QA check refactoring, get the summary constants
    // below from a common class.

    private static final String SUMMARY_DELIMITER = "\t";

    private static final String SUMMARY_FILE_NM = "summary.tsv";

    /** The summary file headings. */
    private static final String[] SUMMARY_HDGS = {
            "Report", "Issue Count"
    };

    private static final String INSTANCE_BROWSER_URL = "cgi-bin/instancebrowser?DB=gk_central&ID=";

    private static final String NL = System.getProperty("line.separator");

    private static final String PROTOCOL = "http";

    private static final String EMAIL_LOOKUP_FILE = "curators.csv";
    
    private static final String MAIL_CONFIG_FILE = "mail.properties";
    
    private static final String DESCRIPTIONS_FILE = "descriptions.tsv";
    
    // The release coordinators.
    private static final Collection<String> COORDINATOR_NAMES =
            new HashSet<String>(2);
    private static final Collection<String> COORDINATOR_EMAILS =
            new HashSet<String>(2);
    
    private static final String COORDINATOR_PRELUDE =
            "The automated QA checks issued the following reports:";
    
    private static final String NONCOORDINATOR_PRELUDE =
            "You are listed as the most recent author in the following automated QA reports:";

    // The last author columns have variant spellings.
    private static final String[] AUTHOR_HEADERS = {
            "MostRecentAuthor",
            "LastAuthor"
    };

    // The DB ID columns have variant spellings.
    private static final String[] DB_ID_HEADERS = { "DB_ID", "DBID" };
    
    private static final Logger logger = LogManager.getLogger();
    
    // A very simple QA report representation.
    static class QAReport {
        List<String> headers;
        List<List<String>> lines;
        
        public QAReport(List<String> headers, List<List<String>> lines) {
            this.headers = headers;
            this.lines = lines;
        }
    
    }

    public static void main(String[] args) throws Exception {
        // Parse command line arguments.
        if (args.length == 0) {
            System.err.println("Missing the reports directory command argument.");
            System.exit(1);
        }
        if (args.length > 1) {
            String extraneous =
                    String.join(", ", Arrays.asList(args).subList(1, args.length));
            System.err.println("Extraneous arguments: " + extraneous);
            System.exit(1);
        }
        String rptsDirArg = args[0];
        
        // The mail properties.
        Properties props = loadProperties();
        
        // The {curator:email} lookup map.
        Map<String, String> emailLookup = null;
        try {
            emailLookup = getCuratorEmailLookup();
        } catch (Exception e) {
            System.err.println("Could not read the curator email file: ");
            System.err.println(e);
            System.exit(1);
        }
        
        // The {curator:email} lookup map.
        Map<String, String> descriptions = null;
        try {
            descriptions = getDescriptions();
        } catch (Exception e) {
            System.err.println("Could not read the descriptions file: ");
            System.err.println(e);
            System.exit(1);
        }
        
        // The {recipient: {report file: html file}} map.
        Map<String, Map<File, File>> notifications =
                new HashMap<String, Map<File, File>>();
        // Coordinators always receive notification, so prepare their
        // notification entry up front.
        for (String coordinator: COORDINATOR_EMAILS) {
            notifications.put(coordinator, new HashMap<File, File>());
        }
        
        // The prefix to prepend to URLs.
        String host = InetAddress.getLocalHost().getCanonicalHostName();
        String hostPrefix = PROTOCOL + "://" + host;

        // The QA reports directory.
        File rptsDir = new File(rptsDirArg);
        if (!rptsDir.exists()) {
            System.err.println("Reports directory not found: " + rptsDir);
            System.exit(1);
        }
        // The report {file name: heading} map.
        Map<String, String> rptTitles = new HashMap<String, String>();
        // The summary files.
        List<File> summaryFiles = new ArrayList<File>();
        // Iterator over each reports subdirectory.
        Collection<File> subdirs = Stream.of(rptsDir.listFiles())
                .filter(File::isDirectory)
                .collect(Collectors.toList());
        for (File dir: subdirs) {
            for (File file: dir.listFiles()) {
                String fileName = file.getName();
                // Only include .tsv files.
                if (fileName.endsWith(".tsv")) {
                    if (SUMMARY_FILE_NM.equals(fileName)) {
                        // Collect and move on.
                        summaryFiles.add(file);
                        continue;
                    }
                    String title = toReportTitle(fileName);
                    rptTitles.put(fileName, title);
                    String displayName = toDisplayName(fileName);
                    String description = descriptions.get(displayName);
                    addNotifications(file, title, emailLookup, description, hostPrefix, notifications);
                }
            }
        }
        
        // Consolidate the summary files.
        File consolidatedSummaryFile = consolidateSummaries(rptsDir, summaryFiles);
        
        // Notify the coordinators and modifiers.
        sendNotifications(notifications, rptTitles, consolidatedSummaryFile,
                hostPrefix, emailLookup, props, rptsDir);
    }

    protected static File consolidateSummaries(File rptsDir, List<File> summaryFiles)
            throws IOException {
        Map<String, Integer> summaryCnts = new HashMap<String, Integer>();
        for (File summaryFile: summaryFiles) {
            QAReport report = getQAReport(summaryFile);
            for (List<String> line: report.lines) {
                summaryCnts.put(line.get(0), new Integer(line.get(1)));
            }
        }
        List<String> summaryRpts = summaryCnts.keySet().stream()
                .sorted()
                .collect(Collectors.toList());
        List<String> summaryLines = new ArrayList<String>();
        for (String rpt: summaryRpts) {
            String title = rpt.replace('_', ' ');
            Integer itemCnt = summaryCnts.get(rpt);
            StringBuffer sb = new StringBuffer();
            sb.append("<tr>");
            sb.append("<td>");
            sb.append(title);
            sb.append("</td>");
            sb.append("<td>");
            sb.append(itemCnt);
            sb.append("</td>");
            sb.append("</tr>");
            sb.append(NL);
            summaryLines.add(sb.toString());
        }
        File consolidatedFile = new File(rptsDir, SUMMARY_NOTIFICATION_FILE_NM);
        List<String> headings = Arrays.asList(SUMMARY_HDGS);
        writeNotificationFile(consolidatedFile, SUMMARY_TITLE, null, headings, summaryLines);
        
        return consolidatedFile;
    }
    
    private static Properties loadProperties() throws IOException {
        File file = new File("resources" + File.separator + MAIL_CONFIG_FILE);
        InputStream is;
        if (file.exists()) {
            is = new FileInputStream(file);
       } else {
            String msg = "The mail configuration file was not found: " + file;
            throw new FileNotFoundException(msg);
       }
        Properties properties = new Properties();
        properties.load(is);
        is.close();

        return properties;
    }

    private static Map<String, String> getCuratorEmailLookup() throws Exception {
        Map<String, String> map = new HashMap<String, String>();
        Consumer<String[]> consumer = new Consumer<String[]>() {

            @Override
            public void accept(String[] line) {
                String name = canonicalizeRecipientName(line[1], line[2]);
                String email = line[3];
                map.put(name, email);
                // The first column is the coordinator flag.
                boolean isCoordinator = Boolean.parseBoolean(line[0]);
                if (isCoordinator) {
                    COORDINATOR_NAMES.add(name);
                    COORDINATOR_EMAILS.add(email);
                }
            }
        
        };
        loadCsv(EMAIL_LOOKUP_FILE, consumer);
        
        return map;
    }

    private static Map<String, String> getDescriptions() throws Exception {
        Map<String, String> map = new HashMap<String, String>();
        Consumer<String[]> consumer = new Consumer<String[]>() {

            @Override
            public void accept(String[] line) {
                map.put(line[0], line[1]);
            }
            
        };
        loadCsv(DESCRIPTIONS_FILE, consumer);
        
        return map;
    }

    /**
     * Reads and parses the given file in the <code>resources</code>
     * directory.
     * 
     * If the file extension is <code>.csv</code>, then the file
     * is read as a comma-separated CSV file. Otherwise, the file
     * is assumed to be tab-separated.
     * 
     * @param fileName the base name of the file to load
     * @param consumer the handler for each file input line
     * @throws Exception
     */
    private static void loadCsv(String fileName, Consumer<String[]> consumer)
            throws Exception {
        File file = new File("resources" + File.separator + fileName);
        Character separator = fileName.endsWith(".csv") ?  ',' : '\t';
        CSVParser parser = new CSVParserBuilder()
                .withSeparator(separator)
                .build();
       FileReader fileRdr = new FileReader(file);
       CSVReader reader = new CSVReaderHeaderAwareBuilder(fileRdr)
                .withCSVParser(parser)
                .build();
        try {
            reader.forEach(consumer);
        } finally {
            reader.close();
        }
    }

    private static QAReport getQAReport(File file) throws IOException {
        InputStream is;
        is = new FileInputStream(file);
        BufferedReader br = new BufferedReader(new InputStreamReader(is));
        String line;
        boolean isFirstLine = true;
        List<String> headers = null;
        List<List<String>> lines = new ArrayList<List<String>>();
        while ((line = br.readLine()) != null) {
            String[] content = line.split(SUMMARY_DELIMITER);
            // The first line is a header.
            if (isFirstLine) {
                isFirstLine = false;
                headers = Arrays.asList(content);
            } else {
                lines.add(Arrays.asList(content));
            }
        }
        br.close();
        is.close();
        
        return new QAReport(headers, lines);
   }

   private static void addNotifications(File rptFile, String title, Map<String, String> emailLookup,
           String description, String hostPrefix, Map<String, Map<File, File>> notifications)
           throws Exception {
       // The QA report.
       QAReport report = getQAReport(rptFile);
       // The column headers.
       List<String> headers = report.headers;
       // The author headers begin with one of the author headers,
       // e.g. MostRecentAuthor_1 is an author header.
       List<Integer> authorIndexes = new ArrayList<Integer>();
       for (String hdr : AUTHOR_HEADERS) {
           int authorNdx = headers.indexOf(hdr);
           if (authorNdx != -1) {
               authorIndexes.add(authorNdx);
           }
       }
       if (!hostPrefix.endsWith("/")) {
           hostPrefix = hostPrefix + "/";
       }
       // The {curator: lines} map.
       Map<String, List<String>> linesMap = new HashMap<String, List<String>>();
       // Coordinators are notified of every file.
       for (String coordinator: COORDINATOR_EMAILS) {
           linesMap.put(coordinator, new ArrayList<String>());
       }
       // The DB ID column indexes match the pattern /.*DB_?ID/.
       int dbIdNdx = getDbIdColumnIndex(report);
       // The DB ID link URL prefix.
       String instUrlPrefix = hostPrefix + INSTANCE_BROWSER_URL;
       // Apportion report lines to the curators.       
       for (List<String> line: report.lines) {
           Set<String> authors = new HashSet<String>();
           for (int authorNdx: authorIndexes) {
               if (authorNdx >= line.size()) {
                   continue;
               }
               String author = line.get(authorNdx);
               if (author != null) {
                   authors.add(author);
               }
           }

           // Convert the report line to HTML.
           String html = createHTMLTableRow(line, dbIdNdx, instUrlPrefix);

           // Coordinators get every line.
           for (String coordinator: COORDINATOR_EMAILS) {
               List<String> lines = linesMap.get(coordinator);
               lines.add(html);
           }

           // Convert the author string on the report to the standardized
           // last,initial format for matching against the curators.
           for (String author: authors) {
               // The author field format pseudo-regex is:
               //   /last, *first|initial(, *date)?/
               String[] authorFields = author.split(", *");
               if (authorFields.length > 1) {
                   String last = authorFields[0];
                   String firstOrInitial = authorFields[1];
                   String canonicalAuthor = canonicalizeRecipientName(last, firstOrInitial);
                   // A coordinator might be an author, but already
                   // has the lines.
                   if (COORDINATOR_NAMES.contains(canonicalAuthor)) {
                       continue;
                   }
                   // The email address.
                   String recipient = emailLookup.get(canonicalAuthor);
                   if (recipient != null) {
                       List<String> lines = linesMap.get(recipient);
                       if (lines == null) {
                           lines = new ArrayList<String>();
                           linesMap.put(recipient, lines);
                       }
                       lines.add(html);
                   }
               }
           }
       }
       
       // The reverse curator {email: name} lookup.
       Map<String, String> emailNameMap = new HashMap<String, String>(emailLookup.size());
       for (Entry<String, String> entry: emailLookup.entrySet()) {
           String name = entry.getKey();
           String address = entry.getValue();
           if (!emailNameMap.containsKey(address)) {
               emailNameMap.put(address, name);
           }
       }

       // Make the curator-specific HTML files.
       for (Entry<String, List<String>> entry: linesMap.entrySet()) {
           String recipient = entry.getKey();
           List<String> lines = entry.getValue();
           File dir = rptFile.getParentFile();
           String fileName = rptFile.getName();
           // The report file base name before the extension.
           String prefix = fileName.split("\\.")[0];
           // Make the custom curator file name.
           String name = emailNameMap.get(recipient);
           String suffix = name.replaceAll("[^\\w]", "").toLowerCase();
           String base = prefix + "_" + suffix;
           String curatorFileName = base + ".html";
           File curatorFile = new File(dir, curatorFileName);
           String effectiveTitle = title;
           if (prefix.endsWith("_diff")) {
               effectiveTitle += " New Issues";
           }
           // Write the custom curator file.
           writeNotificationFile(curatorFile, effectiveTitle, description, report.headers, lines);
           // Add the custom curator file to the
           // {curator: {report file: curator file}} map.
           Map<File, File> curatorNtfs = notifications.get(recipient);
           if (curatorNtfs == null) {
               curatorNtfs = new HashMap<File, File>();
               notifications.put(recipient, curatorNtfs);
           }
           curatorNtfs.put(rptFile, curatorFile);
       }
    }

protected static void writeNotificationFile(File file, String title, String description,
        List<String> headers, List<String> lines) throws IOException {
    String header = createHTMLTableHeader(headers);
    BufferedWriter bw = new BufferedWriter(new FileWriter(file));
       try {
           bw.write("<html>");
           bw.newLine();
           bw.write("<style>");
           bw.newLine();
           bw.write("h1 + p { margin-top: 0; }");
           bw.newLine();
           bw.write("table { border-collapse: collapse; }");
           bw.newLine();
           bw.write("table, th, td { border: 1px solid black; }");
           bw.newLine();
           bw.write("</style>");
           bw.write("<body>");
           bw.newLine();
           bw.write("<h1>");
           bw.write(title);
           bw.write("</h1>");
           bw.newLine();
           // Add the description.
           if (description != null) {
               bw.write("<p>");
               bw.write(description);
               bw.newLine(); 
               bw.write("</p>");
               bw.newLine();
           }
           bw.write("<table>");
           bw.newLine();
           bw.write(" ");
           bw.write(header);
           bw.newLine();
           for (String line: lines) {
               bw.write(" ");
               bw.write(line);
               bw.newLine();
           }
           bw.write("</table>");
           bw.newLine();
           bw.write("</body>");
           bw.write("</html>");
       } finally {
           bw.flush();  
           bw.close();  
       }
}

    private static int getDbIdColumnIndex(QAReport report) {
        for (String dbHdr: DB_ID_HEADERS) {
            for (int i = 0; i < report.headers.size(); i++) {
                String hdr = report.headers.get(i);
                if (hdr.endsWith(dbHdr)) {
                    return i;
                }
            }
        }
        return -1;
    }

    /**
     * Makes an URL-safe version of the name in the form last,initial.
     * @param last
     * @param firstOrInitial
     * @return the standard name representation
     */
    private static String canonicalizeRecipientName(String last, String firstOrInitial) {
        // Make the last name URL-safe by removing non-word characters.
        last = last.replaceAll("[^\\w]", "");
        // Only use an initial.
        Character initial = firstOrInitial.charAt(0);
        return last + "," + initial;
    }

    protected static String createHTMLTableHeader(List<String> headers) {
        StringBuffer sb = new StringBuffer();
        sb.append("<tr>");
        for (String hdr: headers) {
            sb.append("<th>");
            sb.append(hdr);
            sb.append("</th>");
        }
        sb.append("</tr>");

        return sb.toString();
    }

    private static String createHTMLTableRow(List<String> line, int dbIdNdx, String instUrlPrefix) {
        StringBuffer sb = new StringBuffer();
        sb.append("<tr>");
        for (int i = 0; i < line.size(); i++) {
            String col = line.get(i);
            sb.append("<td>");
            if (dbIdNdx == i) {
                sb.append("<a href=");
                sb.append(instUrlPrefix);
                sb.append(col);
                sb.append(">");
                sb.append(col);
                sb.append("</a>");
            } else {
                sb.append(col);
            }
            sb.append("</td>");
        }
        sb.append("</tr>");
        sb.append(NL);

        return sb.toString();
    }

    private static void sendNotifications(Map<String, Map<File, File>> notifications,
            Map<String, String> rptTitles, File summaryFile, String hostPrefix,
            Map<String, String> emailLookup, Properties props, File rptsDir) throws Exception {
        if (!hostPrefix.endsWith("/")) {
            hostPrefix = hostPrefix + "/";
        }
        String dirUrl = hostPrefix + "QAReports/" + rptsDir.getName();
        for (Entry<String, Map<File, File>> ntf: notifications.entrySet()) {
            String recipient = ntf.getKey();
            notify(recipient, dirUrl, props, rptTitles, summaryFile, ntf.getValue());
        }
    }
    
    private static void notify(String recipient, String dirUrl, Properties properties,
            Map<String, String> rptTitles, File summaryFile, Map<File, File> rptHtmlMap) throws Exception {
        Session session = Session.getDefaultInstance(properties);
        MimeMessage message = new MimeMessage(session);
        
        message.setSubject("Reactome Weekly QA");
        if (!dirUrl.endsWith("/")) {
            dirUrl = dirUrl + "/";
        }
        StringBuffer sb = new StringBuffer();
        if (COORDINATOR_EMAILS.contains(recipient)) {
            sb.append(COORDINATOR_PRELUDE + NL + NL);
        } else {
            sb.append(NONCOORDINATOR_PRELUDE + NL + NL);
        }
        // Sort the reports by name.
        Comparator<File> compare = new Comparator<File>() {

            @Override
            public int compare(File f1, File f2) {
                return f1.getName().compareTo(f2.getName());
            }
        
        };
        List<File> rptFiles = rptHtmlMap.keySet().stream()
                .sorted(compare).collect(Collectors.toList());
        // The diff email hyperlink items are captured in a separate group.
        List<String> diffs = new ArrayList<String>();
        if (COORDINATOR_EMAILS.contains(recipient)) {
            // Add the summary hyperlink.
            sb.append("<h3>");
            sb.append("<a href='" + dirUrl + "/" + summaryFile.getName() + "'>");
            sb.append("Summary");
            sb.append("</a>");
            sb.append("</h3>");
            sb.append(NL);
        }
        sb.append("<h3>");
        sb.append("Detail");
        sb.append("</h3>");
        sb.append(NL);
        sb.append("<ul>");
        for (File rptFile: rptFiles) {
            // The QA reports subdirectory.
            String dir = rptFile.getParentFile().getName(); 
            // The report URL prefix.
            String prefix = dirUrl + dir + "/";
            // The HTML file corresponding to the report file.
            File htmlFile = rptHtmlMap.get(rptFile);
            // The report title.
            String title = rptTitles.get(rptFile.getName());
            // The hyperlink list item.
            String links = formatReportItem(recipient, htmlFile, title, prefix, rptFile);
            // Capture diffs for later.
            if (rptFile.getName().endsWith("_diff.tsv")) {
                diffs.add(links);
            } else {
                sb.append(links);
            }
        }
        sb.append("</ul>");
        sb.append(NL);
        // The diff email QA report hyperlinks.
        if (!diffs.isEmpty()) {
            sb.append("<h3>");
            sb.append("New issues");
            sb.append("</h3>");
            sb.append(NL);
            sb.append("<ul>");
            for (String diff: diffs) {
                sb.append(diff);
            }
            sb.append("</ul>");
            sb.append(NL);
        }
        
        message.setContent(sb.toString(), "text/html");
        Address address = new InternetAddress(recipient);
        message.setRecipient(Message.RecipientType.TO, address);
        Transport.send(message);
        logger.info("Sent notification to " + recipient);
   }

   private static String formatReportItem(String recipient, File htmlFile,
            String title, String prefix, File rptFile) {
        StringBuffer sb = new StringBuffer();
        String rptFileName = rptFile.getName();
        String htmlUrl = prefix + htmlFile.getName();
        sb.append("<li>");
        sb.append("<a href='" + htmlUrl + "'>");
        sb.append(title);
        sb.append("</a>");
        // Coordinators get a link to the raw CSV file as well.
        if (!rptFileName.endsWith("_diff.tsv") && COORDINATOR_EMAILS.contains(recipient)) {
            String rptUrl = prefix + rptFileName;
            sb.append(" (<a href='" + rptUrl + "'>");
            sb.append("tsv</a>)");
        }
        sb.append("</li>");
        sb.append(NL);
        
        return sb.toString();
    }
    
    /**
     * Replaces underscores in the report display name portion of the given
     * file name with spaces.
     * 
     * @param fileName the report file name
     * @return the report title
     */
    private static String toReportTitle(String fileName) {
        String baseName = toDisplayName(fileName);
        return baseName.replace("_", " ");
    }

    private static String toDisplayName(String fileName) {
        return fileName.split("(_diff)?\\.")[0];
    }
   
}
