package com.bookbot;

import org.telegram.telegrambots.bots.TelegramLongPollingBot;
import org.telegram.telegrambots.meta.TelegramBotsApi;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.updatesreceivers.DefaultBotSession;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;

public class Main {
    public static void main(String[] args) {
        try {
            TelegramBotsApi botsApi = new TelegramBotsApi(DefaultBotSession.class);
            botsApi.registerBot(new BookBot());
            System.out.println("✅ Book Bot запущен!");
        } catch (TelegramApiException e) {
            e.printStackTrace();
        }
    }
}

class BookBot extends TelegramLongPollingBot {
    private final String botToken = System.getenv("TELEGRAM_BOT_TOKEN");

    @Override
    public void onUpdateReceived(Update update) {
        if (update.hasMessage() && update.getMessage().hasText()) {
            String text = update.getMessage().getText().trim();
            long chatId = update.getMessage().getChatId();

            if (text.equals("/start")) {
                sendStartMessage(chatId);
            } else if (text.startsWith("/search")) {
                String query = text.substring(8).trim();
                if (query.isEmpty()) {
                    sendMessage(chatId, "🔍 Укажите запрос: /search Война и мир");
                } else {
                    searchBooks(chatId, query);
                }
            } else {
                sendMessage(chatId, "📚 Привет! Используйте:\n/start — помощь\n/search <книга> — поиск");
            }
        }
    }

    private void sendStartMessage(long chatId) {
        String text = """
                📚 *Book Bot*
                
                Поиск книг в открытых источниках:
                • Project Gutenberg — классика на английском
                • Open Library — миллионы книг
                
                🔍 Примеры:
                `/search Pride and Prejudice`
                `/search Достоевский`
                
                ⚠️ Все книги в общественном достоянии
                """;
        sendMessage(chatId, text);
    }

    private void searchBooks(long chatId, String query) {
        sendMessage(chatId, String.format("Ищу «%s»... ⏳", query));

        try {
            List<Book> books = searchGutenberg(query);
            books.addAll(searchOpenLibrary(query));

            if (books.isEmpty()) {
                sendMessage(chatId, """
                        ❌ Ничего не найдено.
                        
                        💡 Советы:
                        • Попробуйте английский запрос для зарубежных книг
                        • Ищите по фамилии автора («Толстой», «Shakespeare»)
                        """);
                return;
            }

            int limit = Math.min(books.size(), 3);
            for (int i = 0; i < limit; i++) {
                Book book = books.get(i);
                String caption = String.format(
                    "📖 *%s*\nАвтор: %s\nИсточник: %s",
                    escapeMarkdown(book.title),
                    book.authors.isEmpty() ? "неизвестен" : String.join(", ", book.authors),
                    book.source
                );
                
                if (book.downloadUrl != null) {
                    caption += "\n\n⬇️ Скачать: " + book.downloadUrl;
                }
                if (book.infoUrl != null) {
                    caption += "\n📖 Подробнее: " + book.infoUrl;
                }
                
                sendMessage(chatId, caption);
            }

            if (books.size() > 3) {
                sendMessage(chatId, String.format("🕗 Показано 3 из %d результатов", books.size()));
            }

        } catch (Exception e) {
            e.printStackTrace();
            sendMessage(chatId, "❌ Ошибка поиска. Попробуйте позже.");
        }
    }

    private List<Book> searchGutenberg(String query) throws Exception {
        List<Book> books = new ArrayList<>();
        String url = "https://gutendex.com/books?search=" + query.replace(" ", "%20");

        HttpClient client = HttpClient.newHttpClient();
        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .timeout(java.time.Duration.ofSeconds(10))
            .build();

        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        JsonObject json = JsonParser.parseString(response.body()).getAsJsonObject();
        JsonArray results = json.getAsJsonArray("results");

        for (int i = 0; i < Math.min(results.size(), 2); i++) {
            JsonObject bookJson = results.get(i).getAsJsonObject();
            String title = bookJson.get("title").getAsString();
            
            List<String> authors = new ArrayList<>();
            if (bookJson.has("authors")) {
                JsonArray authorsArr = bookJson.getAsJsonArray("authors");
                for (int j = 0; j < authorsArr.size(); j++) {
                    authors.add(authorsArr.get(j).getAsJsonObject().get("name").getAsString());
                }
            }

            String downloadUrl = null;
            if (bookJson.has("formats")) {
                JsonObject formats = bookJson.getAsJsonObject("formats");
                if (formats.has("application/epub+zip")) {
                    downloadUrl = formats.get("application/epub+zip").getAsString();
                }
            }

            books.add(new Book(
                title,
                authors,
                downloadUrl,
                "https://www.gutenberg.org/ebooks/" + bookJson.get("id").getAsString(),
                "Project Gutenberg"
            ));
        }
        return books;
    }

    private List<Book> searchOpenLibrary(String query) throws Exception {
        List<Book> books = new ArrayList<>();
        String url = "https://openlibrary.org/search.json?q=" + query.replace(" ", "%20") + "&limit=2";

        HttpClient client = HttpClient.newHttpClient();
        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .timeout(java.time.Duration.ofSeconds(10))
            .build();

        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        JsonObject json = JsonParser.parseString(response.body()).getAsJsonObject();
        JsonArray docs = json.getAsJsonArray("docs");

        for (int i = 0; i < Math.min(docs.size(), 2); i++) {
            JsonObject doc = docs.get(i).getAsJsonObject();
            String title = doc.get("title").getAsString();
            
            List<String> authors = new ArrayList<>();
            if (doc.has("author_name")) {
                JsonArray authorsArr = doc.getAsJsonArray("author_name");
                for (int j = 0; j < authorsArr.size(); j++) {
                    authors.add(authorsArr.get(j).getAsString());
                }
            }

            String key = doc.get("key").getAsString();
            books.add(new Book(
                title,
                authors,
                null,
                "https://openlibrary.org" + key,
                "Open Library"
            ));
        }
        return books;
    }

    private void sendMessage(long chatId, String text) {
        try {
            SendMessage message = new SendMessage();
            message.setChatId(String.valueOf(chatId));
            message.setText(text);
            message.setParseMode("Markdown");
            execute(message);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private String escapeMarkdown(String text) {
        return text.replaceAll("([_*\\[\\]()~`>#+\\-=|{}.!])", "\\\\$1");
    }

    @Override
    public String getBotUsername() {
        return "book_bot"; // Можно оставить как есть
    }

    @Override
    public String getBotToken() {
        return botToken;
    }

    static class Book {
        String title;
        List<String> authors;
        String downloadUrl;
        String infoUrl;
        String source;

        Book(String title, List<String> authors, String downloadUrl, String infoUrl, String source) {
            this.title = title;
            this.authors = authors;
            this.downloadUrl = downloadUrl;
            this.infoUrl = infoUrl;
            this.source = source;
        }
    }
}
