<div align="center"># Stock Exchange Simulation</div>

This Java program simulates the operation of a stock exchange. It uses gRPC and socket servers for distributing stock market data.


## Features

- **Real-Time Stock Updates:** Experience instantaneous updates on stock prices delivered over a reliable TCP connection.
- **Efficient Communication:** Seamless client-server interaction is achieved through the use of the gRPC protocol.
- **Portfolio Management:** Effortlessly view and control your stock portfolio within the platform.
- **Order Placement:** Easily execute buy or sell orders for a curated selection of stocks.
- **Comprehensive Data Retrieval:** Stay informed with real-time updates on stock data.


This application allows the user to perform certain actions via the command line. Here's a list of supported functionalities:

- **GET STOCK**: Retrieve stock data.
- **BUY {symbol} {price} {quantity}**: Purchase a specified quantity of stocks.
- **SELL {symbol} {price} {quantity}**: Sell a specified quantity of stocks.
- **HISTORY {date} {hour}**:  View stock prices at a specific date and time.
- **PORTFOLIO**: View user portfolio.
- **BALANCE**: Display current balance.
- **GET ASK {symbol} {number_offer}**: Retrieve a list of ask offers for a specific stock sorted by price.
- **GET BID {symbol} {number_offer}**: Retrieve a list of bids for a specific stock sorted by price.


## Technologies Used

- **Java (JDK)**
- **Maven**
   
