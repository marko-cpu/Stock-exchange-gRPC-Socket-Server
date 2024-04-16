package rs.raf.pds.v5.z2;


import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.StatusRuntimeException;
import rs.raf.pds.v5.z2.gRPC.*;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.Socket;
import java.util.*;


public class StockExchangeClient {
    public String currentUserId;

    private double balance = 5000.00;
    private final ManagedChannel grpcChannel;
    private Socket tcpSocket;
    private final StockExchangeServiceGrpc.StockExchangeServiceBlockingStub blockingStub;
    private volatile boolean phase = false;
    public StockExchangeClient(ManagedChannel channel) {
        blockingStub = StockExchangeServiceGrpc.newBlockingStub(channel);
        this.currentUserId = generateClientId();
        grpcChannel = channel;
        sendUserIdToServer();
    }

    public static void main(String[] args) throws InterruptedException, IOException {
        String host = "localhost";
        int port = 8080;
        ManagedChannel grpcChannel = ManagedChannelBuilder.forAddress(host, port).usePlaintext().build();
        StockExchangeClient grpcClient = new StockExchangeClient(grpcChannel);
        Thread stockDataThread = new Thread(() -> {
            try {
                grpcClient.getStockData();
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        });
        System.out.print("\033[2J\033[1;1H");


        Thread tcpThread = new Thread(() -> {
            try {
                grpcClient.receiveUpdates();
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        });

        tcpThread.start();
        while (!grpcClient.phase) {
            Thread.sleep(100);
        }
        Thread grpcThread = new Thread(() -> {
            Scanner scanner = new Scanner(System.in);
            while (true) {
                String choice = new Scanner(System.in).nextLine();
                String[] parts = choice.split(" ");
                if(choice.toUpperCase().startsWith("GET STOCK")) {
                    try {
                        grpcClient.getStockData();
                    } catch (InterruptedException e) {
                        // TODO Auto-generated catch block
                        e.printStackTrace();
                    }
                }
                else if(choice.toUpperCase().startsWith("BUY")) {
                    if(parts.length == 4)
                        grpcClient.submitBuy(parts[1],Double.parseDouble(parts[2]),Integer.parseInt(parts[3]));
                }
                else if(choice.toUpperCase().startsWith("SELL")) {
                    if(parts.length == 4)
                        grpcClient.submitSell(parts[1],Double.parseDouble(parts[2]),Integer.parseInt(parts[3]));
                }
                else if(choice.toUpperCase().startsWith("HISTORY")) {
                    if(parts.length == 3)
                        grpcClient.getStockDataByDateTime(parts[1],Integer.parseInt(parts[2]));
                }
                else if(choice.toUpperCase().startsWith("PORTFOLIO")) {
                    grpcClient.viewClientPortfolio();
                }
                else if(choice.toUpperCase().startsWith("BALANCE")) {
                    System.out.println("Current balance: $"+grpcClient.getBalance());
                }
                else if(choice.toUpperCase().startsWith("GET ASK")) {
                    if(parts.length == 4) {
                        String symbol = parts[2];
                        int numOffers = Integer.parseInt(parts[3]);
                        grpcClient.getAskList(symbol, numOffers);
                    }
                }
                else if(choice.toUpperCase().startsWith("GET BID")) {
                    if(parts.length == 4) {
                        String symbol = parts[2];
                        int numOffers = Integer.parseInt(parts[3]);
                        grpcClient.getBidList(symbol, numOffers);
                    }
                }
                else {
                    System.out.println("That command does not exist.");
                }
            }
        });

        grpcThread.start();

        try {
            grpcThread.join();
            tcpThread.join();
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
        grpcChannel.shutdown();
    }

    public double getBalance() {
        return balance;
    }

    private void sendUserIdToServer() {
        try {
            tcpSocket = new Socket("localhost", 6666);
            PrintWriter writer = new PrintWriter(tcpSocket.getOutputStream(), true);
            writer.println(currentUserId);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private String generateClientId() {
        return UUID.randomUUID().toString();
    }


    public ClientPortfolio getClientPortfolio(ClientPortfolioRequest request)
    {
        return blockingStub.getClientPortfolio(request);
    }

    private void viewClientPortfolio() {
        ClientPortfolioRequest request = ClientPortfolioRequest.newBuilder()
                .setClientId(currentUserId)
                .build();

        ClientPortfolio portfolio = getClientPortfolio(request);

        System.out.println("Portfolio - Client ID " + portfolio.getClientId() + ":");
        for (ClientStock item : portfolio.getStocksList()) {
            System.out.println("Symbol: " + item.getSymbol() + ", Quantity: " + item.getQuantity());
        }
    }

    private void receiveUpdates() throws InterruptedException {
        System.out.println("TCP thread is running.");
        while (true) {
            try {
                BufferedReader reader = new BufferedReader(new InputStreamReader(tcpSocket.getInputStream()));
                PrintWriter writer = new PrintWriter(tcpSocket.getOutputStream(), true);
                String tcpMessage = reader.readLine();
                if (tcpMessage != null) {
                    if(tcpMessage.startsWith("Enter the symbols")) {
                        System.out.println(tcpMessage.replace("~", " | "));
                        String selectedSymbols;
                        String response;
                        boolean isValidInput = false;
                        do {
                            selectedSymbols = new Scanner(System.in).nextLine();
                            writer.println(selectedSymbols);
                            response = reader.readLine();
                            System.out.println(response);
                            if (!response.startsWith("You are now tracking")) {
                                System.out.println("Enter again:");
                            } else {
                                isValidInput = true;
                            }
                        } while (!isValidInput);
                        synchronized (this) {
                            phase = true;
                        }
                    }
                    else if(tcpMessage.startsWith("Periodical update")) {
                        tcpMessage = tcpMessage.replace("~", "\n");
                        System.out.println(tcpMessage);
                    }
                    else if (tcpMessage.startsWith("User")) {
                        System.out.println("Received trade notification: \n" + tcpMessage);
                    }
                    else if(tcpMessage.startsWith("Congratulations")) {
                        String[] parts = tcpMessage.split(" ");
                        if(parts[2].trim().equals("buy")) {
                            balance -= Double.parseDouble(parts[10].trim().replace("$", ""));
                        }
                        else if(parts[2].trim().equals("sell")) {
                            balance += Double.parseDouble(parts[10].trim().replace("$", ""));
                        }
                        System.out.println("Received trade notification: \n" + tcpMessage);
                    }
                    else {
                        System.out.println("Received stock price change update: \n" + tcpMessage);
                    }
                }

            }
            catch (IOException e) {
                e.printStackTrace();
            }
        }
    }
    private void printStockData(StockData stockData) throws InterruptedException {
        String GREEN = "\u001B[32m";
        String RED = "\u001B[31m";
        String color = stockData.getPriceChange() > 0 ? GREEN + "↑+" : RED + "↓";
        String RESET = "\u001B[0m";
        String dataString = "[ " + stockData.getSymbol() + " ]" + " " + stockData.getCompanyName() + " " +
                " " + stockData.getCurrentPrice() + " " + color + String.format("%.2f", stockData.getPriceChange()) + RESET + " " + "[ " + stockData.getDate() + " " + stockData.getHour() + "h" + " ]";
        int maxLength = 55;
        dataString = String.format("%-" + maxLength + "s", dataString);


        System.out.println(dataString);
        System.out.println("------------------------------------------------------------");


    }



    private void getStockData() throws InterruptedException{

        Empty request = Empty.newBuilder().build();
        StockDataList stockDataList = blockingStub.getStockData(request);

        for (StockData stockData : stockDataList.getStocksList()) {
            printStockData(stockData);
        }

    }
    private void getStockDataByDateTime(String date, int hour) {
        StockDataByDateTimeRequest request = StockDataByDateTimeRequest.newBuilder()
                .setDate(date)
                .setHour(hour)
                .build();

        try {
            StockDataList stockDataList = blockingStub.getStockDataByDateTime(request);

            // Print each stock data from the list
            for (StockData stockData : stockDataList.getStocksList()) {
                printStockData(stockData);
            }
        } catch (StatusRuntimeException | InterruptedException e) {
            System.out.println("Error retrieving stock data: " + e.getCause());
        }
    }



    private void submitBuy(String symbol, double price, int quantity) {
        double totalCost = price * quantity; // Izračunaj ukupnu cijenu

        if (totalCost > balance) {
            System.out.println("Order failure: Your balance is $" + balance + ", and the total cost for this order is $" + totalCost);
            return;
        }

        OrderData orderData = OrderData.newBuilder()
                .setSymbol(symbol)
                .setPrice(price)
                .setQuantity(quantity)
                .setIsBuyOrder(true)
                .setClientId(currentUserId)
                .build();

        OrderRequest request = OrderRequest.newBuilder()
                .setOrder(orderData)
                .build();

        OrderResponse response = blockingStub.submitOrder(request);

        if (response.getSuccess()) {
            balance -= totalCost;
            System.out.println("Buy order success. Remaining balance: $" + balance);
        } else {
            System.out.println("Buy order failed.");
        }
    }

    private void submitSell(String symbol, double price, int quantity) {
        ClientPortfolioRequest requestcp = ClientPortfolioRequest.newBuilder()
                .setClientId(currentUserId)
                .build();
        ClientPortfolio portfolio = getClientPortfolio(requestcp);
        for (ClientStock item : portfolio.getStocksList()) {
            if (item.getSymbol().equalsIgnoreCase(symbol.trim())) {
                if (item.getQuantity() >= quantity) {
                    double totalSaleAmount = price * quantity; // Ukupan iznos prodaje
                    OrderData orderData = OrderData.newBuilder()
                            .setSymbol(symbol)
                            .setPrice(price)
                            .setQuantity(quantity)
                            .setIsBuyOrder(false)
                            .setClientId(currentUserId)
                            .build();

                    OrderRequest request = OrderRequest.newBuilder()
                            .setOrder(orderData)
                            .build();

                    OrderResponse response = blockingStub.submitOrder(request);

                    if (response.getSuccess()) {

                        balance += totalSaleAmount;
                        System.out.println("Sell order success. New balance: $" + balance);
                    } else {
                        System.out.println("Sell order failed.");
                    }
                    return;
                } else {
                    System.out.println("Order failure: Your quantity of " + symbol + " in your portfolio is " + item.getQuantity() + ", which is less than you wanted to sell (" + quantity + ")");
                    return;
                }
            }
        }
        System.out.println("Order failure: You do not possess any " + symbol + " stocks");
    }

    private void getAskList(String symbol, int numOffers) {
        AskListRequest request = AskListRequest.newBuilder()
                .setSymbol(symbol)
                .setNumberOfOffers(numOffers)
                .build();
        AskList askList = blockingStub.getAskList(request);

        // Use a Map to group AskData by price
        Map<Double, List<AskData>> groupedByPrice = new HashMap<>();

        for (AskData askData : askList.getAsksList()) {
            double askPrice = askData.getAskPrice();

            // Group by ask price
            groupedByPrice.computeIfAbsent(askPrice, k -> new ArrayList<>()).add(askData);
        }

        // Display the aggregated information
        for (Map.Entry<Double, List<AskData>> entry : groupedByPrice.entrySet()) {
            double askPrice = entry.getKey();
            List<AskData> groupedAsks = entry.getValue();

            int totalAvailableShares = groupedAsks.stream().mapToInt(AskData::getAvailableShares).sum();

            System.out.println("Symbol: " + symbol + " Ask Price: " + askPrice + " Available Shares: " + totalAvailableShares);
            System.out.println("-----------------------------------------------------");
        }
    }

    private void getBidList(String symbol, int numOffers) {
        BidListRequest request = BidListRequest.newBuilder()
                .setSymbol(symbol)
                .setNumberOfOffers(numOffers)
                .build();
        BidList bidList = blockingStub.getBidList(request);

        // Use a Map to group BidData by price
        Map<Double, List<BidData>> groupedByPrice = new HashMap<>();

        for (BidData bidData : bidList.getBidsList()) {
            double bidPrice = bidData.getBidPrice();

            // Group by bid price
            groupedByPrice.computeIfAbsent(bidPrice, k -> new ArrayList<>()).add(bidData);
        }

        // Display the aggregated information
        for (Map.Entry<Double, List<BidData>> entry : groupedByPrice.entrySet()) {
            double bidPrice = entry.getKey();
            List<BidData> groupedBids = entry.getValue();

            int totalRequestedShares = groupedBids.stream().mapToInt(BidData::getRequestedShares).sum();

            System.out.println("Symbol: " + symbol + " Bid Price: " + bidPrice + " Requested Shares: " + totalRequestedShares);
            System.out.println("------------------------------");
        }
    }




}


