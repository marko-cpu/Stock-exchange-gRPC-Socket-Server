package rs.raf.pds.v5.z2;

import io.grpc.Server;
import io.grpc.ServerBuilder;
import io.grpc.stub.StreamObserver;
import rs.raf.pds.v5.z2.gRPC.*;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;


public class StockExchangeServer extends StockExchangeServiceGrpc.StockExchangeServiceImplBase {

    private List<StockData> stockDataList;
    private List<StockData> historyStockDataList;
    private List<List<StockData>> history;
    private List<OrderData> buyOrders;
    private List<OrderData> sellOrders;
    private List<Trade> tradeList;
    private Map<String, Socket> clientSockets = new ConcurrentHashMap<>();
    private Map<String, Integer> userPortfolio;
    private Map<Socket, Set<String>> clientStockSelections = new HashMap<>();

    private static final long INTERVAL_DURATION = 20000;
    private static long lastUpdateTimestamp = 0L;
    private int currentHour = 1;
    LocalDateTime currentDate = LocalDateTime.now();
    private Timer updateTimer;

    public StockExchangeServer() {
        buyOrders = new ArrayList<>();
        sellOrders = new ArrayList<>();
        tradeList = new ArrayList<>();
        String date = currentDate.format(DateTimeFormatter.ofPattern("dd/MM/yyyy"));
        stockDataList = new ArrayList<>();
        stockDataList.add(createStockData("AAPL", "Apple Inc.", 193.74, 1.14, date,currentHour));
        stockDataList.add(createStockData("MSFT", "Microsoft Corporation", 397.10, 0.39, date,currentHour));
        stockDataList.add(createStockData("GOOGL", "Alphabet Inc.", 146.70, 0.22, date,currentHour));
        stockDataList.add(createStockData("AMZN", "Amazon.com", 144.57, -3.90, date,currentHour));
        stockDataList.add(createStockData("TSLA", "Tesla, Inc.", 237.93, -0.52, date,currentHour));
        stockDataList.add(createStockData("META", "Meta Platforms, Inc.", 347.12, 2.65, date,currentHour));
        stockDataList.add(createStockData("NVDA", "NVIDIA", 479.98, 4.29, date,currentHour));
        stockDataList.add(createStockData("ADBE", "Adobe Inc.", 608.05, -0.57, date,currentHour));
        stockDataList.add(createStockData("MA", "Mastercard Incorporated", 439.83,0.69 , date,currentHour));
        stockDataList.add(createStockData("NFLX", "Netflix Inc.", 487.98,1.04 , date,currentHour));
        historyStockDataList = new ArrayList<>();
        historyStockDataList.addAll(stockDataList);

        history = new ArrayList<>();
        history.add(new ArrayList<>(historyStockDataList));
        clientSockets = new ConcurrentHashMap<>();
        userPortfolio = new ConcurrentHashMap<>();
        updateTimer = new Timer();
        updateTimer.scheduleAtFixedRate(new UpdateTask(), INTERVAL_DURATION, INTERVAL_DURATION);
    }
    public static void main(String[] args) throws IOException, InterruptedException {
        StockExchangeServer stockExchangeServer = new StockExchangeServer();
        Server server = ServerBuilder.forPort(8080).addService(stockExchangeServer).build();
        server.start();

        System.out.println("\u001B[1mBuyStock Exchange gRPC Server started on port 8080\u001B[0m");
        new Thread(stockExchangeServer::startSocketServer).start();

        server.awaitTermination();
    }

    private void startSocketServer() {
        try (ServerSocket serverSocket = new ServerSocket(6666)) {
            System.out.println("\u001B[1mSocket server started on port 6666\u001B[0m");
            while (true) {
                Socket clientSocket = serverSocket.accept();
                System.out.println("\u001B[1mNew client connected:\u001B[0m " + clientSocket.getInetAddress());
                new Thread(() -> handleSocketClient(clientSocket)).start();
                sendTcpStockUpdates();
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }


    private void Updates() {
        System.out.println("\u001B[1mUpdates of stock prices\u001B[0m");

        synchronized (stockDataList) {
            if (stockDataList.size() != historyStockDataList.size()) {
                throw new IllegalStateException("List sizes are not equal.");
            }

            for (int i = 0; i < stockDataList.size(); i++) {
                StockData currentStockData = stockDataList.get(i);
                double stock_diff = currentStockData.getPriceChange();
                String symbol = currentStockData.getSymbol();
                String name = currentStockData.getCompanyName();
                double newPrice = currentStockData.getCurrentPrice();
                String date = currentDate.format(DateTimeFormatter.ofPattern("dd/MM/yyyy"));


                StockData updatedStockData = createStockData(symbol, name, newPrice, stock_diff, date, currentHour);
                historyStockDataList.set(i, updatedStockData);
                stockDataList.set(i, updatedStockData);
            }

            history.add(new ArrayList<>(historyStockDataList));
        }
    }


    private class UpdateTask extends TimerTask {
        @Override
        public void run() {
            currentHour = (currentHour + 1) % 24;
            if (currentHour == 0) { currentDate = currentDate.plusDays(1); }
            Updates();
        }
    }

    @Override
    public void getTradeList(TradeListRequest request, StreamObserver<TradeList> responseObserver) {
        String symbol = request.getSymbol();
        String date = request.getDate();

        TradeList.Builder tradeListBuilder = TradeList.newBuilder();
        List<Trade> filteredTrades = tradeList.stream()
                .filter(trade -> trade.getSymbol().equals(symbol) && trade.getDate().equals(date))
                .collect(Collectors.toList());

        tradeListBuilder.addAllTrades(filteredTrades);

        responseObserver.onNext(tradeListBuilder.build());
        responseObserver.onCompleted();
    }

    private void notifyStockPriceChange(String symbol, double newPrice, double priceChange) {
        String RESET = "\u001B[0m";
        String GREEN = "\u001B[32m";
        String RED = "\u001B[31m";
        String stockUpdate;
        String change = String.format("%.2f",priceChange);
        String new_price = String.format("%.2f",newPrice);
        if (priceChange > 0)
            stockUpdate = symbol + " " + new_price + " " + GREEN + "↑+" + change + RESET;
        else
            stockUpdate = symbol + " " + new_price + " " + RED + "↓" + change + RESET;

        for (Map.Entry<Socket, Set<String>> entry : clientStockSelections.entrySet()) {
            Socket clientSocket = entry.getKey();
            Set<String> selectedStocks = entry.getValue();

            if (selectedStocks.contains(symbol)) {
                try {
                    PrintWriter writer = new PrintWriter(clientSocket.getOutputStream(), true);
                    writer.println(stockUpdate);

                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        }
    }

    private void saveTradesToFile(List<Trade> trades) {
        String fileName = "log.txt";
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm:ss");
        LocalDateTime currentDateTime = LocalDateTime.now();

        try (BufferedWriter writer = Files.newBufferedWriter(Paths.get(fileName), StandardOpenOption.APPEND)) {
            for (Trade trade : trades) {
                String timestamp = currentDateTime.format(formatter);
                String logEntry = String.format("[%s] Symbol: %s, Price: %.2f, Quantity: %d, Buyer: %s, Seller: %s",
                        timestamp, trade.getSymbol(), trade.getPrice(), trade.getQuantity(),
                        trade.getBuyerClientId(), trade.getSellerClientId());

                logEntry += System.lineSeparator();
                writer.write(logEntry);
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private void notifyClientsInTrade(Trade trade) {
        String buyerClientId = trade.getBuyerClientId();
        String sellerClientId = trade.getSellerClientId();
        int quantity = trade.getQuantity();
        String symbol = trade.getSymbol();
        double price = trade.getPrice();

        String buyerNotification = String.format("\u001B[1mYour buy order for %d shares of %s at $%.2f was successful.\u001B[0m",
                quantity, symbol, price);
        String sellerNotification = String.format("\u001B[1mYour sell order for %d shares of %s at $%.2f was successful.\u001B[0m",
                quantity, symbol, price);

        notifyClient(buyerClientId, buyerNotification);
        notifyClient(sellerClientId, sellerNotification);
    }


    private void handleTrade(Trade trade) {
        tradeList.add(trade);
        notifyClientsInTrade(trade);
        saveTradesToFile(Collections.singletonList(trade));
    }

    private void removeOrder(String clientId, String symbol, double price, int quantity, boolean isBuyOrder) {
        List<OrderData> orders = isBuyOrder ? buyOrders : sellOrders;
        orders.removeIf(order -> {
            boolean shouldRemove = order.getClientId().equals(clientId)
                    && order.getSymbol().equals(symbol)
                    && order.getPrice() == price
                    && order.getQuantity() == quantity;
            if (shouldRemove) {
                System.out.println("Removed order - Symbol: " + symbol + ", Price: " + price + ", Quantity: " + quantity);
            }
            return shouldRemove;
        });
    }


    private void notifyClient(String clientId, String notification) {
        Socket clientSocket = clientSockets.get(clientId);
        if (clientSocket != null) {
            try {
                PrintWriter writer = new PrintWriter(clientSocket.getOutputStream(), true);
                writer.println(notification);
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    private void updateAskList(String symbol, double price, int quantity) {
        sellOrders.forEach(order -> {
            if (order.getSymbol().equals(symbol) && order.getPrice() == price) {
                int newQuantity = order.getQuantity() - quantity;
                if (newQuantity <= 0) {
                    sellOrders.remove(order);
                    System.out.println("Removed ask - Symbol: " + symbol + ", Price: " + price);
                } else {
                    sellOrders.set(sellOrders.indexOf(order), order.toBuilder().setQuantity(newQuantity).build());
                    System.out.println("Updated ask list - Symbol: " + symbol + ", Price: " + price + ", Quantity: " + newQuantity);
                }
            }
        });
    }



    @Override
    public void submitOrder(OrderRequest request, StreamObserver<OrderResponse> responseObserver) {
        OrderData orderData = request.getOrder();
        String symbol = orderData.getSymbol();
        int quantity = orderData.getQuantity();
        boolean isBuyOrder = orderData.getIsBuyOrder();
        boolean orderSuccess = true;
        String clientId = orderData.getClientId();

        if (isBuyOrder) {
            buyOrders.add(orderData);
            try {
                updateStockPriceForBuyOrder(orderData);
                updateAskList(symbol, orderData.getPrice(), quantity);
            } catch (Exception e) {
                e.printStackTrace();
            }
        } else {
            sellOrders.add(orderData);
            try {
                updateStockPriceForSellOrder(orderData);
            } catch (Exception e) {
                // TODO Auto-generated catch block
                e.printStackTrace();
            }
        }
        Collections.sort(buyOrders, Comparator.comparingDouble(OrderData::getPrice).reversed());
        Collections.sort(sellOrders, Comparator.comparingDouble(OrderData::getPrice));

        if (orderSuccess) {
            // Update user portfolio
            updateUserPortfolio(clientId, symbol, quantity, isBuyOrder);

        }
        responseObserver.onNext(OrderResponse.newBuilder().setSuccess(orderSuccess).build());
        responseObserver.onCompleted();
    }


    private void handleSocketClient(Socket clientSocket) {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(clientSocket.getInputStream()));
             PrintWriter writer = new PrintWriter(clientSocket.getOutputStream(), true)) {

            String userId = reader.readLine();
            clientSockets.put(userId, clientSocket);

            String symbols = "";
            for (StockData stock_d : stockDataList) {
                symbols += stock_d.getSymbol() + "~";
            }
            writer.println("\u001B[1mEnter the symbols\u001B[0m:~" + symbols + "\u001B[0m");

            //String selectedStocks = reader.readLine();
            List<String> selected = new ArrayList<>();
            List<String> validSymbols = Arrays.asList(symbols.split("~"));
            boolean isValidInput = false;
            String selectedStocks = null;
            while (!isValidInput) {
                selected.clear(); // Clear previous selections
                selectedStocks = reader.readLine();
                boolean allSymbolsValid = true;
                for (String sel : selectedStocks.split(" ")) {
                    String trimmedSymbol = sel.trim().toUpperCase();
                    if (validSymbols.contains(trimmedSymbol)) {
                        selected.add(trimmedSymbol); // Ispravni simboli
                    } else {
                            writer.println("\u001B[31mInvalid symbol: " + "[ " + trimmedSymbol + " ]" + ". Please try again." + "\u001B[0m");
                            allSymbolsValid = false;

                    }
                }
                if (allSymbolsValid) {
                    isValidInput = true;
                }
            }

            clientStockSelections.put(clientSocket, new HashSet<>(selected));

            writer.println("\u001B[32;1mYou are now tracking: " + "[ " + selectedStocks + " ]" + "\u001B[0m");

            while (true) {
                if (clientSocket.isClosed()) {
                    System.out.println("Client disconnected: " + clientSocket.getInetAddress() + " User: " + userId);
                    clientSockets.remove(userId);
                    break;
                }

                Thread.sleep(1000);
            }

        } catch (IOException | InterruptedException e) {
            e.printStackTrace();
        }
    }

    private void updateStockPriceForBuyOrder(OrderData orderData) throws Exception {
        String symbol = orderData.getSymbol();
        double orderPrice = orderData.getPrice();
        int quantity = orderData.getQuantity();

        StockData stockData = getStockDataBySymbol(symbol);
        if (stockData != null) {
            double currentPrice = stockData.getCurrentPrice();
            double newPrice = currentPrice + ((orderPrice-currentPrice) * quantity) / 100.0; //new price calculation function
            String np = String.format("%.2f", newPrice);
            newPrice = Double.parseDouble(np);
            double priceChange = newPrice - currentPrice;
            updateStockData(symbol, newPrice, priceChange);


            notifyStockPriceChange(symbol, newPrice, priceChange);
            checkAndCreateTrades(orderData);
        }
        else {
            throw new Exception();
        }
    }
    private void updateStockPriceForSellOrder(OrderData orderData) throws Exception {
        String symbol = orderData.getSymbol();
        double orderPrice = orderData.getPrice();
        int quantity = orderData.getQuantity();

        StockData stockData = getStockDataBySymbol(symbol);
        if (stockData != null) {
            double currentPrice = stockData.getCurrentPrice();
            double newPrice = currentPrice - ((orderPrice-currentPrice) * quantity) / 100.0;
            String np = String.format("%.2f", newPrice);
            newPrice = Double.parseDouble(np);

            double priceChange = newPrice - currentPrice;
            updateStockData(symbol, newPrice, priceChange);
            notifyStockPriceChange(symbol, newPrice,priceChange);
            checkAndCreateTrades(orderData);
        }
        else {
            throw new Exception();
        }
    }

    private void checkAndCreateTrades(OrderData orderData) {
        String symbol = orderData.getSymbol();
        double orderPrice = orderData.getPrice();
        int quantity = orderData.getQuantity();
        boolean isBuyOrder = orderData.getIsBuyOrder();
        String clientId = orderData.getClientId();

        List<OrderData> matchingOrders = isBuyOrder ? new ArrayList<>(sellOrders) : new ArrayList<>(buyOrders);

        Iterator<OrderData> matchingOrderIterator = matchingOrders.iterator();
        while (matchingOrderIterator.hasNext()) {
            OrderData matchingOrder = matchingOrderIterator.next();

            if (matchingOrder.getSymbol().equals(symbol) && matchingOrder.getPrice() == orderPrice) {
                int availableQuantity = matchingOrder.getQuantity();
                if (availableQuantity >= quantity) {
                    Trade trade = createTrade(symbol, orderPrice, quantity, currentDate.format(DateTimeFormatter.ofPattern("dd/MM/yyyy")), clientId, matchingOrder.getClientId(), isBuyOrder);
                    handleTrade(trade);

                    // Update availableQuantity for matchingOrder
                    matchingOrder = matchingOrder.toBuilder().setQuantity(availableQuantity - quantity).build();

                    if (matchingOrder.getQuantity() == 0) {
                        matchingOrderIterator.remove();
                    }


                    removeOrder(clientId, trade.getSymbol(), trade.getPrice(), trade.getQuantity(), isBuyOrder);
                    break;
                } else {
                    Trade trade = createTrade(symbol, orderPrice, availableQuantity, currentDate.format(DateTimeFormatter.ofPattern("dd/MM/yyyy")), clientId, matchingOrder.getClientId(), isBuyOrder);
                    handleTrade(trade);
                    matchingOrderIterator.remove();

                    // Update quantity for orderData
                    quantity -= availableQuantity;
                    orderData = orderData.toBuilder().setQuantity(quantity).build();

                    checkAndCreateTrades(orderData);
                    break;
                }
            }
        }
    }

    private Trade createTrade(String symbol, double price, int quantity, String date, String buyerClientId, String sellerClientId, boolean isBuyOrder) {
        return Trade.newBuilder()
                .setSymbol(symbol)
                .setPrice(price)
                .setQuantity(quantity)
                .setDate(date)
                .setBuyerClientId(isBuyOrder ? buyerClientId : sellerClientId)
                .setSellerClientId(isBuyOrder ? sellerClientId : buyerClientId)
                .build();
    }



    public void sendTcpStockUpdates() {
        Timer timer = new Timer();
        String GREEN = "\u001B[32m";
        String RED = "\u001B[31m";
        timer.scheduleAtFixedRate(new TimerTask() {
            @Override
            public void run() {
                long currentTimeMillis = System.currentTimeMillis();


                if (currentTimeMillis - lastUpdateTimestamp >= INTERVAL_DURATION) {
                    lastUpdateTimestamp = currentTimeMillis;

                    for (Map.Entry<Socket, Set<String>> entry : clientStockSelections.entrySet()) {
                        Socket clientSocket = entry.getKey();
                        Set<String> selectedStocks = entry.getValue();

                        try {
                            PrintWriter writer = new PrintWriter(clientSocket.getOutputStream(), true);
                            StringBuilder updateMessage = new StringBuilder("\u001B[1mPeriodical update you selected stocks\u001B[0m:~");

                            for (StockData stockData : stockDataList) {
                                if (selectedStocks.contains(stockData.getSymbol())) {

                                    double updatedPriceChange;
                                    if (history.size() > 1) {
                                        // Koristi istorijske podatke za izračunavanje promene cene akcija
                                        StockData previousStockData = history.get(history.size() - 2).stream()
                                                .filter(dummy -> dummy.getSymbol().equals(stockData.getSymbol()))
                                                .findFirst()
                                                .orElse(null);
                                        if (previousStockData != null && previousStockData.getCurrentPrice() != stockData.getCurrentPrice()) {
                                            updatedPriceChange = stockData.getCurrentPrice() - previousStockData.getCurrentPrice();
                                        } else {
                                            updatedPriceChange = stockData.getPriceChange();
                                        }
                                    } else {
                                        // Koristi trenutne podatke za izračunavanje promene cene akcija
                                        updatedPriceChange = stockData.getPriceChange();
                                    }

                                    String color = updatedPriceChange > 0 ? GREEN + "↑+" : RED + "↓";
                                    String RESET = "\u001B[0m";
                                    String dataString =  "[ " +stockData.getSymbol() + " ]" + " " + stockData.getCompanyName() + " " +
                                            " " + stockData.getCurrentPrice() + " " +
                                            color + String.format("%.2f", updatedPriceChange) + RESET +
                                            " " + "[ " + stockData.getDate() + " " + stockData.getHour() + "h" + " ]";

                                    updateMessage.append(dataString).append("~");
                                }
                            }
                            writer.println(updateMessage.toString());
                        } catch (IOException e) {
                            e.printStackTrace();
                        }
                    }
                }
            }
        }, 20000, 20000);
    }

    /** Portfolio **/
    @Override
    public void getClientPortfolio(ClientPortfolioRequest request, StreamObserver<ClientPortfolio> responseObserver) {
        String clientId = request.getClientId();
        if (clientId == null) {
            responseObserver.onError(new IllegalArgumentException("Client ID cannot be null"));
            return;
        }

        List<ClientStock> portfolioItems = new ArrayList<>();
        userPortfolio.forEach((key, value) -> {
            if (key.startsWith(clientId)) {
                String symbol = key.substring(clientId.length());
                ClientStock clientStock = ClientStock.newBuilder()
                        .setSymbol(symbol)
                        .setQuantity(value)
                        .build();
                portfolioItems.add(clientStock);
            }
        });

        ClientPortfolio portfolio = ClientPortfolio.newBuilder()
                .setClientId(clientId)
                .addAllStocks(portfolioItems)
                .build();

        responseObserver.onNext(portfolio);
        responseObserver.onCompleted();
    }


    private void updateUserPortfolio(String clientId, String symbol, int quantity, boolean isBuyOrder) {

        if (quantity <= 0) {
            throw new IllegalArgumentException("The quantity must be greater than zero.");
        }

        // compute metod za ažuriranje vrednosti mape
        userPortfolio.compute(clientId + symbol, (key, currentQuantity) -> {
            int updatedQuantity = currentQuantity != null ? currentQuantity : 0;
            return isBuyOrder ? updatedQuantity + quantity : Math.max(updatedQuantity - quantity, 0);
        });
    }

    /** -------------------------- **/

    /** Stock Data **/
    private StockData createStockData(String symbol, String companyName, double currentPrice, double priceChange, String date, int hour) {
        return StockData.newBuilder()
                .setSymbol(symbol)
                .setCompanyName(companyName)
                .setCurrentPrice(currentPrice)
                .setPriceChange(priceChange)
                .setDate(date)
                .setHour(hour)
                .build();
    }

    @Override
    public void getStockData(Empty request, StreamObserver<StockDataList> responseObserver) {
        if (stockDataList == null) {
            responseObserver.onError(new IllegalStateException("Stock data list is null"));
            return;
        }

        StockDataList.Builder stockDataListBuilder = StockDataList.newBuilder();

        stockDataList.forEach(stockData -> {
            double updatedPriceChange = stockData.getPriceChange();

            StockData updatedStockData = stockData.toBuilder()
                    .setPriceChange(updatedPriceChange)
                    .build();

            stockDataListBuilder.addStocks(updatedStockData);
        });

        responseObserver.onNext(stockDataListBuilder.build());
        responseObserver.onCompleted();
    }


    @Override
    public void getStockDataByDateTime(StockDataByDateTimeRequest request, StreamObserver<StockDataList> responseObserver) {
        String date = request.getDate();
        int hour = request.getHour();

        Optional<List<StockData>> matchingStockDataList = history.stream()
                .filter(stockDataList -> !stockDataList.isEmpty() &&
                        stockDataList.get(0).getDate().equals(date) && stockDataList.get(0).getHour() == hour)
                .findFirst();

        matchingStockDataList.ifPresent(stockDataList ->
                responseObserver.onNext(StockDataList.newBuilder().addAllStocks(stockDataList).build()));

        responseObserver.onCompleted();
    }

    private StockData getStockDataBySymbol(String symbol) {
        return stockDataList.stream()
                .filter(stockData -> stockData.getSymbol().equals(symbol))
                .findFirst()
                .orElse(null);
    }

    private void updateStockData(String symbol, double newPrice, double priceChange) {
        stockDataList.stream()
                .filter(stockData -> stockData.getSymbol().equals(symbol))
                .forEach(stockData -> {
                    StockData.Builder stockDataBuilder = stockData.toBuilder();
                    stockDataBuilder.setCurrentPrice(newPrice);
                    stockDataBuilder.setPriceChange(priceChange);
                    StockData updatedStockData = stockDataBuilder.build();
                    int index = stockDataList.indexOf(stockData);
                    if (index != -1) {
                        stockDataList.set(index, updatedStockData);
                    }
                });
    }

    /** -------------------------------**/


    /** Ask and Bid Data **/
    private AskData createAskData(String symbol, double askPrice, int availableShares) {
        return AskData.newBuilder()
                .setSymbol(symbol)
                .setAskPrice(askPrice)
                .setAvailableShares(availableShares)
                .build();
    }
    private BidData createBidData(String symbol, double bidPrice, int requestedShares) {
        return BidData.newBuilder()
                .setSymbol(symbol)
                .setBidPrice(bidPrice)
                .setRequestedShares(requestedShares)
                .build();
    }

    @Override
    public void getAskList(AskListRequest request, StreamObserver<AskList> responseObserver) {
        String symbol = request.getSymbol();
        int numberOfOffers = request.getNumberOfOffers();

        AskList.Builder askListBuilder = AskList.newBuilder();
        List<OrderData> filteredSellOrders = sellOrders.stream()
                .filter(order -> order.getSymbol().equals(symbol))
                .limit(numberOfOffers)
                .collect(Collectors.toList());

        for (OrderData orderData : filteredSellOrders) {
            AskData askData = createAskData(orderData.getSymbol(), orderData.getPrice(), orderData.getQuantity());
            askListBuilder.addAsks(askData);
        }
        responseObserver.onNext(askListBuilder.build());
        responseObserver.onCompleted();
    }
    @Override
    public void getBidList(BidListRequest request, StreamObserver<BidList> responseObserver) {
        String symbol = request.getSymbol();
        int numberOfOffers = request.getNumberOfOffers();
        BidList.Builder bidListBuilder = BidList.newBuilder();
        List<OrderData> filteredBuyOrders = buyOrders.stream()
                .filter(order -> order.getSymbol().equals(symbol))
                .limit(numberOfOffers)
                .collect(Collectors.toList());
        for (OrderData orderData : filteredBuyOrders) {
            BidData bidData = createBidData(orderData.getSymbol(), orderData.getPrice(), orderData.getQuantity());
            bidListBuilder.addBids(bidData);
        }
        responseObserver.onNext(bidListBuilder.build());
        responseObserver.onCompleted();
    }

    /** -------------------------------**/

}