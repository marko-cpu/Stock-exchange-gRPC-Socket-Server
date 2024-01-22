package rs.raf.pds.v5.z2;

import io.grpc.Server;
import io.grpc.ServerBuilder;
import java.io.IOException;




public class StockExchangeServer {

    public static void main(String[] args) throws IOException, InterruptedException {
        StockExchangeServer stockExchangeServer = new StockExchangeServer();
        Server server = ServerBuilder.forPort(7888).build();
        server.start();

        System.out.println("Stock Exchange gRPC Server started on port 7888");


        server.awaitTermination();
    }
}
