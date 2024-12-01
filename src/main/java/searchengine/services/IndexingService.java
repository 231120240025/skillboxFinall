package searchengine.services;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import searchengine.config.SitesList;
import searchengine.model.Page;
import searchengine.model.Site;
import searchengine.model.Status;
import searchengine.repositories.PageRepository;
import searchengine.repositories.SiteRepository;

import java.net.HttpURLConnection;
import java.net.URL;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class IndexingService {

    private final SitesList sitesList;
    private final SiteRepository siteRepository;
    private final PageRepository pageRepository;

    private volatile boolean indexingInProgress = false;

    @Autowired
    public IndexingService(SitesList sitesList, SiteRepository siteRepository, PageRepository pageRepository) {
        this.sitesList = sitesList;
        this.siteRepository = siteRepository;
        this.pageRepository = pageRepository;
    }

    public synchronized boolean startFullIndexing() {
        if (indexingInProgress) {
            System.out.println("Индексация уже запущена.");
            return false;
        }

        indexingInProgress = true;

        new Thread(() -> {
            try {
                List<searchengine.config.Site> sites = sitesList.getSites();
                for (searchengine.config.Site siteConfig : sites) {
                    processSite(siteConfig.getUrl(), siteConfig.getName());
                }
                System.out.println("Индексация завершена.");
            } catch (Exception e) {
                System.err.println("Ошибка при индексации: " + e.getMessage());
            } finally {
                indexingInProgress = false;
            }
        }).start();

        return true;
    }

    private void processSite(String siteUrl, String siteName) {
        try {
            boolean deleted = deleteSiteData(siteUrl);
            if (deleted) {
                Site site = createOrUpdateSiteRecord(siteUrl, siteName);
                System.out.println("Сайт подготовлен для индексации: " + siteName);

                crawlSite(site, siteUrl);
            }
        } catch (Exception e) {
            System.err.println("Ошибка обработки сайта " + siteUrl + ": " + e.getMessage());
        }
    }

    @SuppressWarnings("resource") // Подавление предупреждения о try-with-resources
    private void crawlSite(Site site, String startUrl) {
        Queue<String> urlsToVisit = new LinkedList<>();
        Set<String> visitedUrls = new HashSet<>();
        urlsToVisit.add(startUrl);

        ScheduledExecutorService executor = Executors.newScheduledThreadPool(1);
        try {
            executor.scheduleWithFixedDelay(() -> {
                if (urlsToVisit.isEmpty()) {
                    executor.shutdown();
                    System.out.println("Обход завершен для сайта: " + site.getUrl());
                    return;
                }

                String currentUrl = urlsToVisit.poll();
                if (currentUrl == null || visitedUrls.contains(currentUrl)) {
                    return;
                }
                visitedUrls.add(currentUrl);

                for (int attempt = 1; attempt <= 3; attempt++) {
                    try {
                        HttpURLConnection connection = (HttpURLConnection) new URL(currentUrl).openConnection();
                        connection.setRequestMethod("GET");
                        connection.setConnectTimeout(10000);
                        connection.setReadTimeout(15000);
                        connection.setRequestProperty("User-Agent", "Mozilla/5.0 (compatible; IndexerBot/1.0)");

                        int statusCode = connection.getResponseCode();
                        String content = new String(connection.getInputStream().readAllBytes());

                        savePage(site, currentUrl.replace(startUrl, ""), statusCode, content);

                        List<String> links = extractLinks(content, startUrl);
                        for (String link : links) {
                            if (!visitedUrls.contains(link)) {
                                urlsToVisit.add(link);
                            }
                        }
                        break;
                    } catch (Exception e) {
                        System.err.println("Попытка " + attempt + " для " + currentUrl + " завершилась ошибкой: " + e.getMessage());
                        if (attempt == 3) {
                            System.err.println("Превышено количество попыток для " + currentUrl);
                        }
                    }
                }
            }, 0, 500, TimeUnit.MILLISECONDS);

            if (!executor.awaitTermination(1, TimeUnit.HOURS)) {
                System.err.println("Некоторые задачи не завершились в отведенное время. Принудительное завершение...");
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            System.err.println("Обход был прерван: " + e.getMessage());
        } finally {
            if (!executor.isShutdown()) {
                executor.shutdownNow();
            }
        }
    }



    private List<String> extractLinks(String content, String baseUrl) {
        List<String> links = new ArrayList<>();
        Pattern pattern = Pattern.compile("href\\s*=\\s*\"(.*?)\"");
        Matcher matcher = pattern.matcher(content);

        while (matcher.find()) {
            String link = matcher.group(1);
            if (link.startsWith("/")) {
                link = baseUrl + link;
            } else if (!link.startsWith("http")) {
                continue; // Пропуск ссылок на внешние ресурсы или относительных ссылок
            }
            if (link.startsWith(baseUrl)) {
                links.add(link);
            }
        }
        return links;
    }

    private Site createOrUpdateSiteRecord(String siteUrl, String siteName) {
        try {
            Site site = new Site();
            site.setUrl(siteUrl);
            site.setName(siteName);
            site.setStatus(Status.INDEXING);
            site.setStatusTime(LocalDateTime.now());
            site.setLastError(null);

            site = siteRepository.save(site);
            System.out.println("Сайт сохранен: " + site);
            return site;
        } catch (Exception e) {
            System.err.println("Ошибка при сохранении сайта " + siteUrl + ": " + e.getMessage());
            return null;
        }
    }

    private boolean deleteSiteData(String siteUrl) {
        try {
            Site site = siteRepository.findByUrl(siteUrl);
            if (site != null) {
                int pageCount = pageRepository.deleteAllBySite(site);
                siteRepository.delete(site);
                System.out.println("Удалено страниц: " + pageCount + " для сайта: " + siteUrl);
                System.out.println("Данные сайта удалены: " + siteUrl);
                return true;
            } else {
                System.out.println("Данные для сайта " + siteUrl + " отсутствуют, ничего не удалено.");
                return false;
            }
        } catch (Exception e) {
            System.err.println("Ошибка при удалении данных сайта " + siteUrl + ": " + e.getMessage());
            return false;
        }
    }

    private void savePage(Site site, String path, int statusCode, String content) {
        try {
            Page page = new Page();
            page.setSite(site);
            page.setPath(path);
            page.setCode(statusCode);
            page.setContent(content);

            pageRepository.save(page);
            System.out.println("Страница сохранена: " + path + " со статусом " + statusCode);
        } catch (Exception e) {
            System.err.println("Ошибка при сохранении страницы " + path + ": " + e.getMessage());
        }
    }
}
