package org.nzbhydra.downloading.downloaders.sabnzbd;

import com.google.common.base.Strings;
import okhttp3.*;
import org.nzbhydra.GenericResponse;
import org.nzbhydra.Jackson;
import org.nzbhydra.downloading.FileDownloadEntity;
import org.nzbhydra.downloading.FileDownloadStatus;
import org.nzbhydra.downloading.downloaders.Downloader;
import org.nzbhydra.downloading.exceptions.DownloaderException;
import org.nzbhydra.downloading.exceptions.DownloaderUnreachableException;
import org.nzbhydra.logging.LoggingMarkers;
import org.nzbhydra.okhttp.HydraOkHttp3ClientHttpRequestFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.io.IOException;
import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Component
public class Sabnzbd extends Downloader {

    private static final Logger logger = LoggerFactory.getLogger(Sabnzbd.class);

    private static final Map<String, FileDownloadStatus> SABNZBD_STATUS_TO_HYDRA_STATUS = new HashMap<>();

    static {
        //TODO Get feedback from Safihre how well mapping would work and how/if any NZBs would be reported which were rejected
        SABNZBD_STATUS_TO_HYDRA_STATUS.put("Grabbing", FileDownloadStatus.REQUESTED);
        SABNZBD_STATUS_TO_HYDRA_STATUS.put("Queued", FileDownloadStatus.NZB_ADDED);
        SABNZBD_STATUS_TO_HYDRA_STATUS.put("Paused", FileDownloadStatus.NZB_ADDED);
        SABNZBD_STATUS_TO_HYDRA_STATUS.put("Checking", FileDownloadStatus.NZB_ADDED);
        SABNZBD_STATUS_TO_HYDRA_STATUS.put("Downloading", FileDownloadStatus.NZB_ADDED);
        SABNZBD_STATUS_TO_HYDRA_STATUS.put("QuickCheck", FileDownloadStatus.NZB_ADDED);
        SABNZBD_STATUS_TO_HYDRA_STATUS.put("Verifying", FileDownloadStatus.NZB_ADDED);
        SABNZBD_STATUS_TO_HYDRA_STATUS.put("Repairing", FileDownloadStatus.NZB_ADDED);
        SABNZBD_STATUS_TO_HYDRA_STATUS.put("Fetching", FileDownloadStatus.NZB_ADDED);// Fetching additional blocks
        SABNZBD_STATUS_TO_HYDRA_STATUS.put("Extracting", FileDownloadStatus.NZB_ADDED);
        SABNZBD_STATUS_TO_HYDRA_STATUS.put("Moving", FileDownloadStatus.NZB_ADDED);
        SABNZBD_STATUS_TO_HYDRA_STATUS.put("Running", FileDownloadStatus.NZB_ADDED);// Running PP Script
        SABNZBD_STATUS_TO_HYDRA_STATUS.put("Completed", FileDownloadStatus.CONTENT_DOWNLOAD_SUCCESSFUL);
        SABNZBD_STATUS_TO_HYDRA_STATUS.put("Failed", FileDownloadStatus.CONTENT_DOWNLOAD_ERROR);
    }


    @Autowired
    private RestTemplate restTemplate;
    @Autowired
    private HydraOkHttp3ClientHttpRequestFactory requestFactory;

    private UriComponentsBuilder getBaseUrl() {
        UriComponentsBuilder builder = UriComponentsBuilder.fromHttpUrl(downloaderConfig.getUrl()).pathSegment("api");
        if (!Strings.isNullOrEmpty(downloaderConfig.getApiKey())) {
            builder.queryParam("apikey", downloaderConfig.getApiKey());
        }
        builder.queryParam("output", "json");
        return builder;
    }

    @Override
    public String addLink(String url, String title, String category) throws DownloaderException {
        logger.debug("Sending link for NZB {} to sabnzbd", title);
        title = suffixNzbToTitle(title);
        UriComponentsBuilder urlBuilder = getBaseUrl();
        urlBuilder.queryParam("mode", "addurl").queryParam("name", url).queryParam("nzbname", title).queryParam("priority", "-100");
        if (!Strings.isNullOrEmpty(category)) {
            urlBuilder.queryParam("cat", category);
        }
        String nzoId = sendAddNzbLinkCommand(urlBuilder, null, HttpMethod.POST);
        logger.info("Successfully added link {} for NZB \"{}\" to sabnzbd queue with ID {}", url, title, nzoId);
        return nzoId;
    }

    protected String suffixNzbToTitle(String title) {
        if (!title.toLowerCase().endsWith(".nzbd")) {
            title += ".nzb";
        }
        return title;
    }

    private String sendAddNzbLinkCommand(UriComponentsBuilder urlBuilder, HttpEntity httpEntity, HttpMethod httpMethod) throws DownloaderException {
        try {
            URI url = urlBuilder.build().encode().toUri();
            ResponseEntity<AddNzbResponse> response = restTemplate.exchange(url, httpMethod, httpEntity, AddNzbResponse.class);
            if (!response.getStatusCode().is2xxSuccessful()) {
                throw new DownloaderException("Downloader returned status code " + response.getStatusCode());
            }
            if (!response.getBody().isStatus()) {
                throw new DownloaderException("Downloader says NZB was not added successfully.");
            }
            if (response.getBody().getNzoIds().isEmpty()) {
                throw new DownloaderException("Sabnzbd says NZB was added successfully but didn't return an NZO ID");
            }
            return response.getBody().getNzoIds().get(0);
        } catch (RestClientException e) {
            throw new DownloaderUnreachableException("Error while adding NZB(s): " + e.getMessage());
        }
    }

    @Override
    public String addNzb(byte[] fileContent, String title, String category) throws DownloaderException {
        //Using OKHTTP here because RestTemplate wouldn't work
        logger.debug("Uploading NZB {} to sabnzbd", title);
        UriComponentsBuilder urlBuilder = getBaseUrl();
        title = suffixNzbToTitle(title);
        urlBuilder.queryParam("mode", "addfile").queryParam("nzbname", title).queryParam("priority", "-100");
        if (!Strings.isNullOrEmpty(category)) {
            urlBuilder.queryParam("cat", category);
        }
        RequestBody formBody = new MultipartBody.Builder().addFormDataPart("name", title, RequestBody.create(MediaType.parse(org.springframework.http.MediaType.APPLICATION_XML_VALUE), fileContent)).build();
        Request request = new Request.Builder()
                .url(urlBuilder.toUriString())
                .post(formBody)
                .build();
        OkHttpClient client = requestFactory.getOkHttpClientBuilder(urlBuilder.build().encode().toUri()).build();
        try (Response response = client.newCall(request).execute(); ResponseBody body = response.body()) {
            if (!response.isSuccessful()) {
                throw new DownloaderException("Downloader returned status code " + response.code() + " and message " + response.message());
            }
            AddNzbResponse addNzbResponse = Jackson.JSON_MAPPER.readValue(body.string(), AddNzbResponse.class);
            if (addNzbResponse.getNzoIds().isEmpty()) {
                throw new DownloaderException("Sabnzbd says NZB was added successfully but didn't return an NZO ID");
            }
            String nzoId = addNzbResponse.getNzoIds().get(0);
            logger.info("Successfully added NZB \"{}\" to sabnzbd queue with ID {}", title, nzoId);
            return nzoId;
        } catch (IOException e) {
            throw new DownloaderException("Error while communicating with downloader: " + e.getMessage());
        }

    }

    @Override
    public List<DownloaderEntry> getHistory(Instant earliestDownloadTime) throws DownloaderException {
        //TODO: Store and use last_history_update? See https://sabnzbd.org/wiki/advanced/api#history_main
        UriComponentsBuilder uriBuilder = getBaseUrl().queryParam("mode", "history");
        HistoryResponse queueResponse = callSabnzb(uriBuilder.build().toUri(), HistoryResponse.class);
        List<DownloaderEntry> historyEntries = new ArrayList<>();
        for (HistoryEntry historyEntry : queueResponse.getHistory().getSlots()) {
            DownloaderEntry entry = new DownloaderEntry();
            entry.setNzbId(historyEntry.getNzo_id());
            entry.setNzbName(historyEntry.getName()); //nzbName ends with .nzb
            entry.setStatus(historyEntry.getStatus());
            entry.setTime(Instant.ofEpochSecond(historyEntry.getCompleted()));
            if (entry.getTime().isBefore(earliestDownloadTime)) {
                logger.debug(LoggingMarkers.DOWNLOAD_STATUS_UPDATE, "Stopping transforming history entries because the current history entry is from {} which is before earliest download to check which is from {}", entry.getTime(), earliestDownloadTime);
                return historyEntries;
            }
            historyEntries.add(entry);
        }

        return historyEntries;
    }

    @Override
    public List<DownloaderEntry> getQueue(Instant earliestDownload) throws DownloaderException {
        UriComponentsBuilder uriBuilder = getBaseUrl().queryParam("mode", "queue");
        QueueResponse queueResponse = callSabnzb(uriBuilder.build().toUri(), QueueResponse.class);
        List<DownloaderEntry> historyEntries = new ArrayList<>();
        for (QueueEntry slotEntry : queueResponse.getQueue().getSlots()) {
            DownloaderEntry entry = new DownloaderEntry();
            entry.setNzbId(slotEntry.getNzo_id());
            entry.setNzbName(slotEntry.getFilename()); //Does not end with NZB
            entry.setStatus(slotEntry.getStatus());
            historyEntries.add(entry);
        }

        return historyEntries;
    }

    protected <T> T callSabnzb(URI uri, Class<T> responseType) throws DownloaderException{
        try {
            return restTemplate.getForObject(uri, responseType);
        } catch (RestClientException e) {
            throw new DownloaderException("Error while calling sabNZBd", e);
        }
    }

    @Override
    protected FileDownloadStatus getDownloadStatusFromDownloaderEntry(DownloaderEntry entry, StatusCheckType statusCheckType) {
        return SABNZBD_STATUS_TO_HYDRA_STATUS.get(entry.getStatus());
    }

    @Override
    protected boolean isDownloadMatchingDownloaderEntry(FileDownloadEntity download, DownloaderEntry entry) {
        boolean idMatches = download.getExternalId() != null && download.getExternalId().equals(entry.getNzbId());
        boolean nameMatches = download.getSearchResult().getTitle() != null && download.getSearchResult().getTitle().equals(entry.getNzbName());
        return idMatches || nameMatches;
    }


    @Override
    public GenericResponse checkConnection() {
        logger.debug("Checking connection");
        UriComponentsBuilder baseUrl = getBaseUrl();
        try {
            restTemplate.exchange(baseUrl.queryParam("mode", "get_cats").toUriString(), HttpMethod.GET, null, CategoriesResponse.class);
            logger.info("Connection check with sabnzbd using URL {} successful", baseUrl.toUriString());
            return new GenericResponse(true, null);
        } catch (RestClientException e) {
            logger.info("Connection check with sabnzbd using URL {} failed: {}", baseUrl.toUriString(), e.getMessage());
            return new GenericResponse(false, e.getMessage());
        }
    }

    @Override
    public List<String> getCategories() {
        logger.debug("Loading list of categories");
        UriComponentsBuilder uriBuilder = getBaseUrl().queryParam("mode", "get_cats");
        return restTemplate.getForObject(uriBuilder.build().toUri(), CategoriesResponse.class).getCategories();
    }

}
