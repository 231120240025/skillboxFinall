package searchengine.services;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import searchengine.config.SitesList;
import searchengine.model.Page;
import searchengine.model.Site;
import searchengine.model.Status;
import searchengine.repositories.PageRepository;
import searchengine.repositories.SiteRepository;

import java.time.LocalDateTime;
import java.util.List;

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

                // Пример добавления страниц с динамическими HTTP-статусами
                savePage(site, "/index.html", generateStatusCode(), "<html>Главная страница</html>");
                savePage(site, "/about.html", generateStatusCode(), "<html>О компании</html>");
                savePage(site, "/contact.html", generateStatusCode(), "<html>Контакты</html>");
            }
        } catch (Exception e) {
            System.err.println("Ошибка обработки сайта " + siteUrl + ": " + e.getMessage());
        }
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

    private int generateStatusCode() {
        // Генерация случайного HTTP-статуса для тестирования
        int[] statusCodes = {200, 404, 500, 302};
        return statusCodes[(int) (Math.random() * statusCodes.length)];
    }
}
