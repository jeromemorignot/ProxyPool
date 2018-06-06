package victor.proxy.pool;

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.io.PushbackInputStream;
import java.io.StringReader;
import java.math.BigInteger;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Random;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import java.util.Map.Entry;


import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang.ArrayUtils;
import org.apache.commons.lang.builder.ToStringBuilder;
import org.apache.log4j.Logger;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;
import org.json.simple.parser.ParseException;

public class Main {
	
	private static final Logger logger = Logger.getLogger(Main.class);
	
	private static void exit() {
		System.exit(-121);
	}
	
	private static double updateCoinsTime;
	private static int poolPort;
	private static Map<String, Coin> coinMap;

	private static Properties props = new Properties();

	private static void readProperties() throws ParseException {
		try {
			String propStr = FileUtils.readFileToString(new File("pool.properties"));
			props.load(new StringReader(propStr.replace("\\","\\\\")));
		} catch (FileNotFoundException e) {
			logger.error("no pool.properties found");
			exit();
		} catch (IOException e) {
			logger.error("error reading properties: " + e);
			exit();
		}
		String extpools = props.getProperty("extpools").trim();
		coinMap = Collections.synchronizedMap(Coin.parse(extpools));
		if (coinMap == null) {
			logger.error(ParseException.ERROR_UNEXPECTED_TOKEN);
			throw new ParseException(ParseException.ERROR_UNEXPECTED_TOKEN);
		}
		updateCoinsTime = Double.valueOf(props.getProperty("minehub.update.frequency").trim());		
		poolPort = Integer.valueOf(props.getProperty("pool.port").trim());
	}
	
	private static String getStringByURL(URL jsonURL) throws IOException {
		HttpURLConnection conn;
		String jsonText = null;
		InputStream httpIn = null;
		
		try {
			conn = (HttpURLConnection) jsonURL.openConnection();
			
			conn.setRequestMethod("GET");
	        conn.setRequestProperty("Content-Type", 
	                   "application/x-www-form-urlencoded");
	        conn.setRequestProperty("Content-Language", "en-US"); 
	        conn.setRequestProperty("User-Agent",
	                "Mozilla/5.0 (Windows NT 5.1) AppleWebKit/535.11 (KHTML, like Gecko) Chrome/17.0.963.56 Safari/535.11");
	        conn.setUseCaches(false);
	        conn.setDoInput(true);
	        conn.setDoOutput(true);
			
			httpIn = new BufferedInputStream(conn.getInputStream());     
			jsonText = IOUtils.toString(httpIn);
		} finally {
			IOUtils.closeQuietly(httpIn);
		}
		
		return jsonText;
	}
	
//	private static boolean shouldSwitch(double currentProf, double newProf, long timePassed /* millis */) {
//		boolean result = newProf / currentProf > neededIncrease(timePassed);
//		return result;
//	}
//	
//	private static double neededIncrease(long timePassed) {
//		return 1 + 20 / (timePassed / 1000d + 3);
//	}
	
	private static class Coin {
		String coinAbr, host, login, password;
		int port;
		
		public static Map<String, Coin> parse(String str) {
			Map<String, Coin> coins = new HashMap<>();
			String[] coinTexts = str.split(","); // ZEC=user:password@host:port,ZEN=user:password@host:port
			for(String coinText : coinTexts) {
				Coin c = new Coin();
				try { 
					coinText = coinText.trim();
					String[] pair = coinText.split("=");
					c.coinAbr = pair[0];
					pair = coinText.substring(coinText.indexOf('=')+1).split("@");
					String[] userAndPass = pair[0].split(":");
					c.login = userAndPass[0];
					c.password = userAndPass[1];
					pair = pair[1].split(":"); 
					c.host = pair[0];
					c.port = Integer.valueOf(pair[1]);
					coins.put(c.coinAbr, c);
				} catch (Exception e) {
					return null;
				}
			}
			
			
			return coins;
		}
		
		@Override
		public String toString() {
			return ToStringBuilder.reflectionToString(this);
		}
		
		public Coin clone() {
			Coin c = new Coin();
			c.coinAbr = Coin.this.coinAbr;
			c.host = Coin.this.host;
			c.login = Coin.this.login;
			c.password = Coin.this.password;
			c.port = Coin.this.port;
			return c;
		}
	}
	
	private static ThreadLocal<Map<String, Coin>> minerCoins = new ThreadLocal<>();
	
	private static ThreadLocal<String> extranonce1 = new ThreadLocal<>();
	
	private static String getCurrentCoin() {
		synchronized (Main.coins) {
			for(CoinWithProfit coin : coins.keySet()) {
				if (minerCoins.get().containsKey(coin.coinAbr)) {
					return coin.coinAbr;
				}
			}
		}
		return null;
	}
	
	private static PrintWriter switchToCoin(Coin newCoin, PrintWriter minerPw) throws ParseException, IOException {
		
		Socket socketRemote = new Socket(newCoin.host, newCoin.port);
		PrintWriter pwRemote = new PrintWriter(socketRemote.getOutputStream());
		BufferedReader inRemote = new BufferedReader(new InputStreamReader(socketRemote.getInputStream()));

		String subscribeMessage = String.format("{\"method\":\"mining.subscribe\",\"id\":1,\"params\":[\"%s\",null,\"%s\",%d]}", extranonce1.get(), newCoin.host, newCoin.port);
		logger.debug("switchpool->pool" + subscribeMessage);
		pwRemote.println(subscribeMessage);
		pwRemote.flush();

		String lineRemote = inRemote.readLine();
		logger.debug("switchpool<-pool" + lineRemote);
		JSONObject rootJsonRemote = (JSONObject)new JSONParser().parse(lineRemote);
		String extranonce1 = ((JSONArray)rootJsonRemote.get("result")).get(1).toString();
		
		String authoriseMessageRem = String.format("{\"id\":2,\"method\":\"mining.authorize\",\"params\":[\"%s\",\"%s\"]}", newCoin.login, newCoin.password);
		logger.debug("switchpool->pool" + authoriseMessageRem);
		pwRemote.println(authoriseMessageRem);
		pwRemote.flush();
		
//		lineRemote = inRemote.readLine();
//		logger.debug("switchpool<-pool" + lineRemote);
//		minerPw.println(lineRemote);
//		minerPw.flush();
		
		String setExtranonceMessage = String.format("{\"method\":\"mining.set_extranonce\",\"id\":3,\"params\":[\"%s\", null]}", extranonce1);
		logger.debug("miner<-switchpool" + setExtranonceMessage);
		minerPw.println(setExtranonceMessage);
		minerPw.flush();
		
		new Thread(() -> {
			String lineRemote2 = null;
			JSONParser parser = new JSONParser();
			try {
				while ((lineRemote2 = inRemote.readLine()) != null) {
					JSONObject json = (JSONObject)parser.parse(lineRemote2);
					Object id = json.get("id");
					if (id != null && "2".equals(id.toString())) {
						logger.debug("(null)<-...<-pool" + lineRemote2);
						continue;
					}
					logger.debug("miner<-...<-pool" + lineRemote2);
					minerPw.println(lineRemote2);
					minerPw.flush();
				}
			} catch (IOException e) {
				// TODO Do nothing??, PrintWriter must have been closed in
				// other thread
			} catch (ParseException e) {
				logger.error("Error parsing message: " +lineRemote2);
				try { socketRemote.close();} catch (IOException e1) {}
				minerPw.close();
			}
		}).start();
		
		return pwRemote;
	}
	
	private static void processSocketConnection(final Socket clientSocket) throws IOException, ParseException {
		BufferedReader in = new BufferedReader(new InputStreamReader(clientSocket.getInputStream()));
		PrintWriter pw = new PrintWriter(clientSocket.getOutputStream());
		StringBuilder sb = new StringBuilder();
		JSONParser parser = new JSONParser();
		long startTime, currentTime, feePeriodStartTime, lastKeepingShareTime;
		startTime = currentTime = feePeriodStartTime = lastKeepingShareTime = System.currentTimeMillis();
		long feeTime = 0;
		
		Map<String, Coin> coinMap = new HashMap<>();
		for(Entry<String, Coin> entry : Main.coinMap.entrySet()) {
			coinMap.put(entry.getKey(), entry.getValue().clone());
		}
		
		minerCoins.set(coinMap);
		Set<String> mainCoinAbrs = new HashSet<>();
		synchronized (Main.coins) {
			for(CoinWithProfit pair : Main.coins.keySet()) {
				mainCoinAbrs.add(pair.coinAbr);
			}
		}
		minerCoins.get().keySet().retainAll(mainCoinAbrs);
		String message = "Coins supported: " + minerCoins.get().keySet();
		logger.debug(message);
		
		Coin currentCoin = minerCoins.get().values().iterator().next();		

		Socket socketRemote = new Socket(currentCoin.host, currentCoin.port);
		class Wrapper<T> { // synchronized
			private T t;
			public Wrapper(T t) {
				this.t = t;
			}
			synchronized T get() {
				return t;
			}
			synchronized void set(T t) {
				this.t = t;
			}
			@Override
			public String toString() {
				return t.toString();
			}
		}
		Wrapper<PrintWriter> remoteSocketPw = new Wrapper(new PrintWriter(socketRemote.getOutputStream()));
		BufferedReader inRemote = new BufferedReader(new InputStreamReader(socketRemote.getInputStream()));
		
		try {
		String line;
		while((line = in.readLine())!=null) {
				long timeInterval = System.currentTimeMillis() - currentTime;
				currentTime = System.currentTimeMillis();				
			
				JSONObject json = (JSONObject)parser.parse(line);
				logger.debug("miner->switchpool" + json);
				String id = json.get("id").toString();
				String method = json.get("method").toString();
				if ("mining.subscribe".equalsIgnoreCase(method)) {					
					final Random rnd = new Random();
					String extranonceGenerated = BigInteger.valueOf(rnd.nextInt(1<<(3*8+5))).toString(16);
					extranonceGenerated = "0000000000".substring(extranonceGenerated.length()) + extranonceGenerated;						
					((JSONArray)json.get("params")).set(0, extranonceGenerated);
					
					if (((JSONArray)json.get("params")).size() == 4) {
						((JSONArray)json.get("params")).set(2, currentCoin.host);
						((JSONArray)json.get("params")).set(3, currentCoin.port);
					}
					line = json.toString();
					logger.debug("switchpool->pool" + line);
					remoteSocketPw.get().println(line);
					remoteSocketPw.get().flush();
					String resp = inRemote.readLine();
					logger.debug("switchpool<-pool" + resp);
					JSONObject rootJsonRemote = (JSONObject)parser.parse(resp);
					JSONArray result = ((JSONArray)rootJsonRemote.get("result"));
					String extranonce1 = result.get(1).toString();
					Main.extranonce1.set(extranonce1);
					logger.debug("miner<-switchpool" + resp);
					pw.println(resp);
					pw.flush();
					
					new Thread(()->{
						JSONParser parser2 = new JSONParser();
						String lineRemote2 = null;
						try {
							while ((lineRemote2 = inRemote.readLine()) != null) {

//								logger.debug("switchpool<-pool" + lineRemote2);
//								JSONObject json2 = (JSONObject)parser2.parse(lineRemote2);
//								Object id2 = json2.get("id");
//								if (id2 != null && "1".equals(id2.toString())) {
//									JSONArray result2 = ((JSONArray)json.get("result"));
//									if (result2.size() > 1) {
//										result2.set(0, result2.get(1));
//										lineRemote2 = json2.toString();
//									}
//								}
								
								logger.debug("miner<-...<-switchpool" + lineRemote2);
								pw.println(lineRemote2);
								pw.flush();								
													
							}
							
						} catch (IOException e) {
							logger.debug("Pool connection closed, possibly because of a currency switch: " + e);
						} 
//						catch (ParseException e) {
//							logger.error("Error parsing message: " + lineRemote2);
//							try { socketRemote.close();} catch (IOException e1) {}
//							pw.close();
//						}
					}).start();
					
					continue;
				}			
				if("mining.authorize".equalsIgnoreCase(method)) {
					JSONArray params = (JSONArray)json.get("params");
					String login = params.get(0).toString();
					for(Coin coin : minerCoins.get().values()) {
						coin.login = coin.login.replaceAll("LOGIN", login);
					}
//					((JSONArray)json.get("params")).set(0, currentCoin.login);
//					((JSONArray)json.get("params")).set(1, currentCoin.password);
//					line = json.toString();
				}
				if("mining.submit".equalsIgnoreCase(method)) {
					if (currentCoin == null) throw new IOException("Forcing disconnect due to wrong internal state");					
					((JSONArray)json.get("params")).set(0, currentCoin.login);
					line = json.toString();
					logger.info(String.format("Share found!  (%s)  %s", clientSocket.getInetAddress(), currentCoin.login));
				}
				
				logger.debug("miner->...->pool" + line);
				if (remoteSocketPw.get() != null) {
					remoteSocketPw.get().println(line);
					remoteSocketPw.get().flush();
				} else {
					logger.error("Somehow remotePw was not initialized and we're going to loose the message: " + line);
				}
								
				if("mining.submit".equalsIgnoreCase(method)) {
					
					String newCoin = getCurrentCoin();
					
					if (!currentCoin.coinAbr.equalsIgnoreCase(newCoin)) {

						remoteSocketPw.get().close();
						
						logger.debug(String.format("Switching coins! %s to %s", currentCoin.coinAbr, newCoin));
						synchronized (Main.coins) {
							currentCoin = minerCoins.get().get(newCoin);
						}
						remoteSocketPw.set( switchToCoin(currentCoin, pw) );
					}
				}
						
		}
		} finally {
			if (remoteSocketPw != null && remoteSocketPw.get() != null) {
				logger.debug("closing pool connection");
				remoteSocketPw.get().close();
			}
			logger.debug("closing miner connection");
			clientSocket.close();
		}
		
		
	}
	
	private static void handleConnections() {
		ServerSocket serverSocket = null;
		try {
			//serverSocket = (SSLServerSocket)SSLServerSocketFactory.getDefault().createServerSocket(7721);
			serverSocket = new ServerSocket(poolPort);
			Socket clientSocket;
			while((clientSocket = serverSocket.accept()) != null) {
				final Socket s = clientSocket;
				new Thread(()->{ 
					try {
						logger.info(String.format("miner (%s) connected ", s.getInetAddress()));
						processSocketConnection(s);
					} catch (IOException | ParseException e2) {
						logger.info(String.format("miner (%s) connection error (he might have shut it down): %s", s.getInetAddress(),  e2));
					} catch (Exception e) {
						logger.error(String.format("miner (%s) unexpected error during processing connection: %s %s", s.getInetAddress(), 
								e, ArrayUtils.toString(e.getStackTrace())));
					}
				}).start();
			}
			
		} catch (IOException e) {
			logger.fatal("FATAL ERROR: " + e);
		} finally {
			try {
				if (serverSocket != null) serverSocket.close();
			} catch (IOException e) {
				logger.error("Couldn't close server socket: " + e);
			}
		}
	}
	
	
	
	
	private static Map<CoinWithProfit, String> coins = new TreeMap<>((a,b)->{
		return b.profitability.compareTo(a.profitability);
	});
	
	private static URL jsonURL;
	
	private static void updateCoinsListOnce() throws IOException, ParseException {
		JSONParser parser = new JSONParser();
		String jsonText = getStringByURL(jsonURL);
		JSONObject topJson = (JSONObject)parser.parse(jsonText);
		
		Map<String, CoinWithProfit> coinsNew = new HashMap<>();
		
		for (Object coinWrap : topJson.entrySet()) {
			Entry<Object, Object> coinPairVals = (Entry<Object, Object>) coinWrap;
			String coinAbr = (String) coinPairVals.getKey();
			double profitability = Double.valueOf(((JSONArray)(coinPairVals.getValue())).get(0).toString().trim());
			coinsNew.put(coinAbr, new CoinWithProfit(coinAbr, profitability));
		}
		synchronized (Main.coins) {
			
			for(Entry<CoinWithProfit, String> entry : new HashSet<>(Main.coins.entrySet())) {
				String coinAbr;
				if (coinsNew.containsKey(coinAbr = entry.getValue())) {
					CoinWithProfit oldCoin = entry.getKey();
					CoinWithProfit newCoin = coinsNew.remove(coinAbr);
					Main.coins.remove(oldCoin);
					Main.coins.put(newCoin, coinAbr);
				}
			}
			for(Entry<String, CoinWithProfit> entry : coinsNew.entrySet()) {
				Main.coins.put(entry.getValue(), entry.getKey());
			}
		}
		
	}
	
	private static void updateCoinsList() {
		String jsonURLstr = props.getProperty("minehub.json.url");
		try { 
			jsonURL = new URL(jsonURLstr);
		} catch (MalformedURLException e) {
			logger.error("URL is incorrect : " + e);
			exit();
		}

		new Thread(()->{
			while(true) {
				try {
					try {
						updateCoinsListOnce();
//						logger.debug(coins); // too much logs
					} catch (IOException | ParseException e) {
						logger.error("Error reading coins info from MineHub :" + e);
					}
					try {
						Thread.sleep((long) (updateCoinsTime * 1000));
					} catch (InterruptedException e1) {
						logger.error("coins update waiting period interrupted :" + e1);
					}
				} catch (Exception e) {
					logger.fatal("Coin prices udpate loop crashed, repeating in 1 second : " + e);
					e.printStackTrace();
					try { Thread.sleep((long) (updateCoinsTime * 1000)); } catch (InterruptedException e1) { }
				}
			}
		}).start();
	}
	
	public static void main(String[] args) {
		logger.info("Proxy Pool v0.3 (no NiceHash support)!");
		try {
			readProperties();
		} catch (ParseException e) {
			logger.fatal("Error parsing properties: " + e);
			exit();
		}
		updateCoinsList();

		handleConnections();

	}

}