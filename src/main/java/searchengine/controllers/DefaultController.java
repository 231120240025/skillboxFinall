package searchengine.controllers;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import searchengine.services.IndexingService;

import java.util.concurrent.locks.ReentrantLock;
import java.util.HashMap;
import java.util.Map;

@RestController
public class DefaultController {

    private final IndexingService indexingService;
    private final ReentrantLock lock = new ReentrantLock();

    @Autowired
    public DefaultController(IndexingService indexingService) {
        this.indexingService = indexingService;
    }

    @GetMapping("/api/startIndexing")
    public Map<String, Object> startIndexing() {
        Map<String, Object> response = new HashMap<>();

        if (!lock.tryLock()) {
            response.put("result", false);
            response.put("error", "Индексация уже запущена");
            System.err.println("Попытка запустить индексацию, когда она уже запущена.");
            return response;
        }

        try {
            boolean started = indexingService.startFullIndexing();
            if (started) {
                response.put("result", true);
                System.out.println("Индексация успешно запущена.");
            } else {
                response.put("result", false);
                response.put("error", "Не удалось запустить индексацию");
                System.err.println("Не удалось запустить индексацию.");
            }
        } finally {
            lock.unlock();
        }

        return response;
    }
}
