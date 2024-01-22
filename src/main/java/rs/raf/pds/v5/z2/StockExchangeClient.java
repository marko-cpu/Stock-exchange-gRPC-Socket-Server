package rs.raf.pds.v5.z2;

import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;


public class StockExchangeClient {
	
	public static void main(String[] args) {
        ManagedChannel channel = ManagedChannelBuilder.forAddress("localhost", 8090)
          .usePlaintext()
          .build();

    }

}
