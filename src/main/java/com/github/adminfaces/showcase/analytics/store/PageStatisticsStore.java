package com.github.adminfaces.showcase.analytics.store;

import com.github.adminfaces.showcase.analytics.model.PageStats;
import com.github.adminfaces.showcase.analytics.model.PageView;
import com.github.adminfaces.showcase.analytics.model.PageViewCountry;
import com.github.adminfaces.showcase.filter.BlackListFilter;
import com.github.adminfaces.showcase.model.StringWrapper;
import org.apache.commons.io.FileUtils;
import org.primefaces.event.FileUploadEvent;
import org.primefaces.model.DefaultStreamedContent;
import org.primefaces.model.StreamedContent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.PostConstruct;
import javax.ejb.Schedule;
import javax.enterprise.context.ApplicationScoped;
import javax.enterprise.context.RequestScoped;
import javax.enterprise.context.SessionScoped;
import javax.enterprise.inject.Produces;
import javax.faces.application.FacesMessage;
import javax.faces.context.FacesContext;
import javax.inject.Named;
import javax.json.*;
import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

import static com.github.adminfaces.showcase.analytics.util.FileUtils.unzip;
import static com.github.adminfaces.template.util.Assert.has;

/**
 * Created by rmpestano on 01/05/17.
 */
@ApplicationScoped
@Named
public class PageStatisticsStore implements Serializable {

    private Map<String, PageStats> pageStatisticsMap; //viewId by statistics map
    private static final Logger log = LoggerFactory.getLogger(PageStatisticsStore.class.getName());
    private final String pagesStatsFilePath = (System.getenv("OPENSHIFT_DATA_DIR") != null ? System.getenv("OPENSHIFT_DATA_DIR") : System.getProperty("user.home")) + "/page-stats.json".replaceAll("//", "/");
    private static final SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd");
    private List<String> pageViewCountries;
    private Map<Integer, Integer> totalVisitorsByMonth;//key is month and value is total
    private Map<Integer, Integer> uniqueVisitorsByMonth;//key is month and value is total
    private Map<String, Integer> totalVisitorsByCountry;
    private String geoJsonCache;
    private static final SimpleDateFormat sdf = new SimpleDateFormat("MM/dd/yyyy");
    private List<Integer> yearsWithStatistics;
    private List<PageStats> pageStatsFilteredByDate;
    private StreamedContent pageStatsFile;
    private Calendar statisticsCollectedSince;


    @PostConstruct
    public void initStatistics() {
        pageStatisticsMap = new ConcurrentHashMap<>();
        log.info("Using {} as page statistics file store.", pagesStatsFilePath);
        statisticsCollectedSince = Calendar.getInstance();
        try {
            File statisticsFile = new File(pagesStatsFilePath);
            if (!statisticsFile.exists()) {
                statisticsFile.createNewFile();
            }
            JsonArray persistedPageStats = Json.createReader(new InputStreamReader(new FileInputStream(statisticsFile), "UTF-8")).readObject().getJsonArray("statistics");
            for (JsonValue jsonValue : persistedPageStats) {
                JsonObject jsonObject = (JsonObject) jsonValue;
                PageStats pageStats = pageStatisticsMap.get(jsonObject.getString("viewId"));
                if (pageStats == null) {
                    pageStats = new PageStats(jsonObject.getString("viewId"));
                }
                JsonArray pageViewsJson = jsonObject.getJsonArray("pageViews");
                List<PageView> pageViews = new ArrayList<>();
                for (JsonValue value : pageViewsJson) {
                    JsonObject object = (JsonObject) value;
                    if (object == null || object.get("ip") == null) {
                        continue;
                    }
                    PageView pageView = new PageView(object.getString("ip"));
                    Calendar c = Calendar.getInstance();
                    try {
                        c.setTime(dateFormat.parse(object.getString("date")));
                    } catch (NumberFormatException nfe) {
                        continue; //skip object without date
                    }
                    pageView.setDate(c);
                    pageView.setCountry(object.containsKey("country") ? object.getString("country") : "");//backward compat
                    pageView.setCity(object.containsKey("city") ? object.getString("city") : "");//backward compat
                    pageView.setLat(object.containsKey("lat") ? object.getString("lat") : "");//backward compat
                    pageView.setLon(object.containsKey("lon") ? object.getString("lon") : "");//backward compat
                    pageView.setHasIpInfo(object.containsKey("hasIpInfo") ? object.getBoolean("hasIpInfo") : false);//backward compat
                    pageViews.add(pageView);
                }
                if (pageStats.getPageViews() != null) {
                    pageStats.getPageViews().addAll(pageViews);
                } else {
                    pageStats.setPageViews(pageViews);
                }

                pageStatisticsMap.put(pageStats.getViewId(), pageStats);
            }
        } catch (Exception e) {
            log.warn("Could not load page statistics "+e.getMessage());
        } finally {
            log.info("Finished reading page statistics store.");
        }
    }

    public Calendar getStatisticsCollectedSince() {
        return statisticsCollectedSince;
    }


    private boolean considerDate(String date) {
        if (!has(date)) {
            return false;
        }
        try {
            Calendar viewedOn = Calendar.getInstance();
            viewedOn.setTime(dateFormat.parse(date));
            return viewedOn.get(Calendar.YEAR) == Calendar.getInstance().get(Calendar.YEAR);
        } catch (ParseException e) {
            log.warn("Could not parse page view date {}", date);
            return false;
        }
    }


    public PageStats getPageStats(String viewId) {
        return pageStatisticsMap.get(viewId);
    }

    public void addPageView(String viewId, PageView pageView) {
        PageStats pageStats = pageStatisticsMap.get(viewId);
        if (pageStats == null) {
            pageStats = new PageStats(viewId);
            pageStatisticsMap.put(viewId, pageStats);
        }
        pageStats.addPageView(pageView);
    }

    @Schedule(hour = "*/1", persistent = false)
    public void persistPageStatistics() {
        if (pageStatisticsMap == null || pageStatisticsMap.isEmpty()) {
            return;//in some situation the schedule is called before statistics is initialized
        }
        long initial = System.currentTimeMillis();

        List<PageStats> pageStatsCopy = null;
        synchronized (pageStatisticsMap) {
            List<PageStats> originalList = new ArrayList<>(pageStatisticsMap.values());
            pageStatsCopy = copyPageStats(originalList);
        }
        int numRecordsUpdated = 0;
        try {
            JsonArrayBuilder pageStatsJsonArray = Json.createArrayBuilder();
            for (PageStats pageStats : pageStatsCopy) {
                JsonArrayBuilder pageViewsJsonArray = Json.createArrayBuilder();
                for (PageView pageView : pageStats.getPageViews()) {
                    if (!has(pageView.getIp()) || BlackListFilter.isBlocked(pageView.getIp())) {
                        continue;
                    }
                    boolean infoUpdated = queryAdditionalPageViewInfo(pageView);
                    if (infoUpdated) {
                        numRecordsUpdated++;
                    }
                    JsonObject pageViewJsonObject = Json.createObjectBuilder()
                            .add("ip", pageView.getIp())
                            .add("date", dateFormat.format(pageView.getDate().getTime()))
                            .add("country", pageView.getCountry() != null ? pageView.getCountry() : "")
                            .add("city", pageView.getCity() != null ? pageView.getCity() : "")
                            .add("lat", pageView.getLat() != null ? pageView.getLat() : "")
                            .add("lon", pageView.getLon() != null ? pageView.getLon() : "")
                            .add("hasIpInfo", pageView.getHasIpInfo()).build();


                    pageViewsJsonArray.add(pageViewJsonObject);
                }
                JsonObject pageStatsJson = Json.createObjectBuilder()
                        .add("viewId", pageStats.getViewId())
                        .add("pageViews", pageViewsJsonArray.build()).build();
                pageStatsJsonArray.add(pageStatsJson);
            }

            if (numRecordsUpdated > 0) {
                synchronized (pageStatisticsMap) {
                    for (PageStats pageStats : pageStatsCopy) {
                        pageStatisticsMap.put(pageStats.getViewId(), pageStats);
                    }
                }

                FileUtils.writeStringToFile(new File(pagesStatsFilePath), Json.createObjectBuilder().add("statistics", pageStatsJsonArray.build()).build().toString().replaceAll("�", ""), "UTF-8");
                resetStatstistics();
                updateGeoJsonCache();
            }

        } catch (Exception e) {
            log.error("Could not persist statistics in path " + pagesStatsFilePath, e);
        } finally {
            log.info("{} page statistics updated in {} seconds.", numRecordsUpdated, (System.currentTimeMillis() - initial) / 1000.0d);
        }
    }

    /**
     * Stats data must be bigger than 1MB to backup
     *
     * @param pageStatsJson
     * @return
     */
    private boolean hasStatistics(File pageStatsJson) {
        return pageStatsJson != null && pageStatsJson.length() > 1024 * 1024;
    }

    @Schedule(hour = "*/6", minute = "15", persistent = false)
    public void calculatePageViews() {
        List<PageStats> pageStatsCopy = null;
        synchronized (pageStatisticsMap) {
            List<PageStats> originalList = new ArrayList<>(pageStatisticsMap.values());
            pageStatsCopy = copyPageStats(originalList);
            for (PageStats pageStats : pageStatsCopy) {
                pageStats.initPageViewsCount();
            }
        }
    }

    private List<PageStats> copyPageStats(List<PageStats> originalList) {
        List<PageStats> pageStatsCopy = new ArrayList<>(originalList.size());
        for (PageStats stats : originalList) {
            PageStats pageStats = new PageStats(stats.getViewId());
            pageStats.setPageViews(new ArrayList<PageView>());
            for (PageView originalView : stats.getPageViews()) {
                PageView pageViewCopy = new PageView(originalView.getIp());
                pageViewCopy.setCity(originalView.getCity());
                pageViewCopy.setCountry(originalView.getCountry());
                pageViewCopy.setDate(originalView.getDate());
                pageViewCopy.setHasIpInfo(originalView.getHasIpInfo());
                pageViewCopy.setIp(originalView.getIp());
                pageViewCopy.setLat(originalView.getLat());
                pageViewCopy.setLon(originalView.getLon());
                pageStats.addPageView(pageViewCopy);
            }
            pageStatsCopy.add(pageStats);
        }

        return pageStatsCopy;
    }

    //force statistics reload
    public void resetStatstistics() {
        totalVisitorsByCountry = null;
        totalVisitorsByMonth = null;
        uniqueVisitorsByMonth = null;
        pageStatsFilteredByDate = null;
        geoJsonCache = null;
    }

    private void loadPageViewCountries(Calendar dateToConsider) {
        if (pageViewCountries == null) {
            pageViewCountries = new ArrayList<>();
        }
        for (PageStats pageStats : allPageStatsByDate(dateToConsider)) {
            for (PageView pageView : pageStats.getPageViews()) {
                if (has(pageView.getCountry()) && !pageViewCountries.contains(pageView.getCountry())) {
                    pageViewCountries.add(pageView.getCountry());
                }
            }
        }
    }

    /**
     * @param pageView
     * @return boolean representing the info was updated
     */
    private boolean queryAdditionalPageViewInfo(PageView pageView) {
        if (shouldIgnorePageInfo(pageView)) {
            return false;
        }
        StringBuilder ipApiQuery = new StringBuilder("http://ip-api.com/json/");
        boolean result = false;
        if (!pageView.getIp().contains(",")) {//only one ip returned
            ipApiQuery.append(pageView.getIp());
            result = callIpApi(ipApiQuery.toString(), pageView);
        } else { //multiple ips
            String[] ips = pageView.getIp().toString().split(",");
            for (String ip : ips) {
                result = callIpApi(ipApiQuery + ip.toString().trim(), pageView);
                if (result) {
                    pageView.setIp(ip);
                    break;
                }
            }
        }
        return result;
    }

    private boolean shouldIgnorePageInfo(PageView pageView) {
        return pageView.getHasIpInfo() || pageView.getIp().equals("127.0.0.1") || pageView.getIp().contains("localhost") || pageView.getIp().contains("unknown") || pageView.getIp().startsWith("10.");
    }

    private boolean callIpApi(String ipApiQuery, PageView pageView) {
        if (!has(ipApiQuery)) {
            return false;
        }

        HttpURLConnection connection = null;//using url connection to avoid (JavaEE 6) JAX-RS client api conflicts
        BufferedReader rd = null;
        try {
            URL url = new URL(ipApiQuery);
            connection = (HttpURLConnection) url.openConnection();
            connection.setConnectTimeout(5000);
            connection.setRequestMethod("GET");
            connection.setRequestProperty("Content-Type",
                    "application/json");

            connection.setRequestProperty("Accept-Charset", "UTF-8");

            InputStream is = connection.getInputStream();
            rd = new BufferedReader(new InputStreamReader(is, "UTF-8"));
            StringBuilder json = new StringBuilder();
            String line;
            while ((line = rd.readLine()) != null) {
                json.append(line);
            }
            InputStream jsonStream = new ByteArrayInputStream(json.toString().getBytes("UTF-8"));
            JsonObject jsonObject = Json.createReader(jsonStream).readObject();
            if (jsonObject.containsKey("status") && !jsonObject.getString("status").equals("fail")) {
                pageView.setCountry(jsonObject.getString("country"));
                pageView.setCity(jsonObject.getString("city"));
                pageView.setLat(jsonObject.getJsonNumber("lat").toString());
                pageView.setLon(jsonObject.getJsonNumber("lon").toString());
                pageView.setHasIpInfo(true);
                Thread.sleep(375);//sleep to not exceed query limits (150 per minute)
                return true;
            } else if (jsonObject.containsKey("message")) {
                if (jsonObject.getString("message").contains("private range")) {
                    pageView.setHasIpInfo(true);
                    return true;
                }
                log.warn("IpApi query {} failed with message: " + jsonObject.getString("message"), ipApiQuery);
            }

        } catch (Exception e) {
            log.error("Could not get additional info from IP API request:" + ipApiQuery, e);
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
            if (rd != null) {
                try {
                    rd.close();
                } catch (IOException e) {
                    log.error("Problem closing buffered reader", e);
                }
            }
        }
        return false;
    }


    /**
     * Brings page stats filtered by a given date. Null means all statistics
     *
     * @param dateToConsider
     * @return
     */
    public List<PageStats> allPageStatsByDate(Calendar dateToConsider) {

        if (pageStatsFilteredByDate == null) {
            if (dateToConsider == null) {
                pageStatsFilteredByDate = new ArrayList<>(pageStatisticsMap.values());
            } else {
                //-1 means all years/months
                int yearToConsider = dateToConsider.get(Calendar.YEAR);
                int monthToConsider = dateToConsider.get(Calendar.MONTH);
                pageStatsFilteredByDate = new ArrayList<>();
                for (PageStats pageStats : pageStatisticsMap.values()) {
                    PageStats filteredPageStats = new PageStats(pageStats.getViewId());
                    List<PageView> filteredPageViews = new ArrayList<>();
                    for (PageView pageView : pageStats.getPageViews()) {
                        int year = pageView.getDate().get(Calendar.YEAR);
                        int month = pageView.getDate().get(Calendar.MONTH);
                        if (month == monthToConsider && year == yearToConsider) {
                            filteredPageViews.add(pageView);
                        }
                    }

                    filteredPageStats.setPageViews(filteredPageViews);
                    pageStatsFilteredByDate.add(filteredPageStats);
                }
            }
        }


        return pageStatsFilteredByDate;
    }

    public List<String> getPageViewCountries(Calendar dateToConside) {
        if (pageViewCountries == null) {
            loadPageViewCountries(dateToConside);
        }
        return pageViewCountries;
    }

    public List<PageStats> getPageStatsWithCountries(Calendar dateToConsider) {
        List<PageStats> pageStatsWithCountries = new ArrayList<>();
        for (PageStats stats : allPageStatsByDate(dateToConsider)) {
            PageStats pageStats = new PageStats(stats.getViewId());
            List<PageView> pageViews = new ArrayList<>();
            for (PageView pageView : stats.getPageViews()) {
                if (has(pageView.getCountry())) {
                    pageViews.add(pageView);
                }
            }
            pageStats.setPageViews(pageViews);
            pageStatsWithCountries.add(pageStats);
        }
        return pageStatsWithCountries;
    }

    public Map<Integer, Integer> getTotalVisitorsByMonth(Calendar dateToConsider) {
        if (totalVisitorsByMonth == null) {
            //-1 means all years/months
            int currentYear = dateToConsider != null ? dateToConsider.get(Calendar.YEAR) : -1;
            totalVisitorsByMonth = new HashMap<>();
            for (int i = 0; i <= 11; i++) {
                totalVisitorsByMonth.put(i, 0);
            }
            for (PageStats pageStats : pageStatsFilteredByDate) {
                for (PageView pageView : pageStats.getPageViews()) {
                    if (currentYear != -1 && pageView.getDate().get(Calendar.YEAR) != currentYear || !has(pageView.getIp())) {
                        continue;
                    }
                    int pageViewMonth = pageView.getDate().get(Calendar.MONTH);
                    totalVisitorsByMonth.put(pageViewMonth, totalVisitorsByMonth.get(pageViewMonth) + 1);
                }
            }
        }

        return totalVisitorsByMonth;
    }

    public Map<Integer, Integer> getUniqueVisitorsByMonth(Calendar dateToConsider) {
        if (uniqueVisitorsByMonth == null) {
            //currentYear == -1 means all years
            int currentYear = dateToConsider != null ? dateToConsider.get(Calendar.YEAR) : -1;
            List<String> ipList = new ArrayList<>();
            uniqueVisitorsByMonth = new HashMap<>();
            for (int i = 0; i <= 11; i++) {
                uniqueVisitorsByMonth.put(i, 0);
            }
            for (PageStats pageStats : pageStatsFilteredByDate) {
                for (PageView pageView : pageStats.getPageViews()) {
                    if (currentYear != -1 && pageView.getDate().get(Calendar.YEAR) != currentYear || !has(pageView.getIp()) || ipList.contains(pageView.getIp())) {
                        continue;
                    }
                    int pageViewMonth = pageView.getDate().get(Calendar.MONTH);
                    ipList.add(pageView.getIp());
                    uniqueVisitorsByMonth.put(pageViewMonth, uniqueVisitorsByMonth.get(pageViewMonth) + 1);
                }
            }
        }
        return uniqueVisitorsByMonth;
    }

    public Map<String, Integer> getTotalVisitorsByCountry() {
        if (totalVisitorsByCountry == null) {
            totalVisitorsByCountry = new HashMap<>();
            for (PageStats pageStats : pageStatsFilteredByDate) {
                List<PageViewCountry> pageViewCountryList = pageStats.getPageViewCountryList();
                for (PageViewCountry pageViewCountry : pageViewCountryList) {
                    String country = pageViewCountry.getCountry();
                    if (!totalVisitorsByCountry.containsKey(country)) {
                        totalVisitorsByCountry.put(country, 0);
                    }
                    totalVisitorsByCountry.put(country, totalVisitorsByCountry.get(country) + pageViewCountry.getViewCount());
                }
            }
        }
        return totalVisitorsByCountry;
    }

    public String getGeoJsonCache(Calendar dateToConside) {
        if (geoJsonCache == null) {
            JsonArrayBuilder geoJsonLayer = Json.createArrayBuilder();
            for (PageStats stats : allPageStatsByDate(dateToConside)) {
                for (PageView pageView : stats.getPageViews()) {
                    if (!has(pageView.getCountry()) || !has(pageView.getLat())) {
                        continue;
                    }
                    JsonObjectBuilder geoJson = Json.createObjectBuilder()
                            .add("type", "Feature");
                    JsonObjectBuilder geometry = Json.createObjectBuilder()
                            .add("type", "Point")
                            .add("coordinates", Json.createArrayBuilder()
                                    .add(new Double(pageView.getLon()))
                                    .add(new Double(pageView.getLat())));
                    geoJson.add("geometry", geometry);
                    JsonObjectBuilder properties = Json.createObjectBuilder()
                            .add("country", pageView.getCountry())
                            .add("city", pageView.getCity())
                            .add("page", stats.getViewId())
                            .add("date", sdf.format(pageView.getDate().getTime()));
                    geoJson.add("properties", properties);
                    geoJsonLayer.add(geoJson);
                }
            }
            geoJsonCache = geoJsonLayer.build().toString();
        }
        return geoJsonCache;
    }

    public void updateGeoJsonCache() {
        geoJsonCache = null;
    }

    public List<Integer> getYearsWithStatistics() {
        if (yearsWithStatistics == null) {
            yearsWithStatistics = new ArrayList<>();
            yearsWithStatistics.add(Calendar.getInstance().get(Calendar.YEAR));
            for (PageStats pageStats : allPageStatsByDate(null)) {
                for (PageView pageView : pageStats.getPageViews()) {
                    Integer year = pageView.getDate().get(Calendar.YEAR);
                    if (!yearsWithStatistics.contains(year)) {
                        yearsWithStatistics.add(year);
                    }
                }
            }
            Collections.sort(yearsWithStatistics);
        }

        return yearsWithStatistics;
    }

    public void handleFileUpload(FileUploadEvent event) throws IOException {
        try (InputStream in = event.getFile().getInputstream()) {
            unzip(in, pagesStatsFilePath);
            //Files.copy(in,Paths.get(pagesStatsFilePath), StandardCopyOption.REPLACE_EXISTING);
            initStatistics();
            FacesMessage message = new FacesMessage("Succesful", event.getFile().getFileName() + " is uploaded. Size (KB): " + event.getFile().getSize() / 1024f);
            FacesContext.getCurrentInstance().addMessage(null, message);
        }
    }

    public void prepareDownload() throws FileNotFoundException {
        InputStream stream = new FileInputStream(new File(pagesStatsFilePath));
        pageStatsFile = new DefaultStreamedContent(stream, "application/json", "page-stats.json");
    }

    public StreamedContent getPageStatsFile() {
        return pageStatsFile;
    }

    @Produces
    @RequestScoped
    @Named("now")
    public Date now() {
        return new Date();
    }

    @Produces
    @SessionScoped
    @Named("hostname")
    public StringWrapper hostname() {
        return new StringWrapper(System.getenv("HOSTNAME"));
    }
}
