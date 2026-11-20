package org.example;

import com.google.gson.*;
import me.xdrop.fuzzywuzzy.FuzzySearch;
import scales.dev.NetworkUtil;
import scales.dev.SpreadsheetUtil;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

public class Main {
  private static final String API = "https://webshop.one.com/api/v2/cluster/myexoticfruit.com/domain/myexoticfruit.com/product";
  private static final Map<String, String> ONE_COM_HEADERS = Map.of(
          "x-one-ws-client-type", "WebshopShopfront",
          "x-one-ws-preview", "false",
          "Content-Type", "application/json",
          "Referer", "https://myexoticfruit.com/",
          "Origin", "https://myexoticfruit.com",
          "Accept", "application/json, text/plain, */*",
          "User-Agent", "Mozilla/5.0"
  );

  private static final Map<String, String> ITEM_DEFAULT_PARAMS = Map.of(
          "sortBy", "MANUAL",
          "sort", "ASC",
          "pageOffset", "0",
          "pageSize", "70",
          "filters", "{}"
  );

  private static final String[] IGNORED_WORDS = {"fresh", "fruit", "citrus"};
  private static final String IGNORED_WORDS_REGEX = "(?i)" + String.join("|", IGNORED_WORDS);

  // looks for numbers followed by an optional space and a weight
  private static final Pattern KILOGRAM_REGEX =
          Pattern.compile("(\\d++(?:\\.\\d++)?)\\s*kg\\b", Pattern.CASE_INSENSITIVE);
  private static final Pattern GRAM_REGEX =
          Pattern.compile("(\\d++(?:\\.\\d++)?)\\s*g\\b", Pattern.CASE_INSENSITIVE);

  private static final Gson GSON = new Gson();

  private static final Map<String, Double> SHOP_PRODUCTS = getProductNamePriceMap();

  public static void main(String[] args) throws IOException {
    Path storageDir = Path.of(System.getProperty("user.home"), ".mef");
    Files.createDirectories(storageDir);

    Path file = storageDir.resolve("profits.json");
    JsonArray storage = Files.exists(file)
            ? GSON.fromJson(Files.newBufferedReader(file), JsonArray.class)
            : new JsonArray();

    ArrayList<Map<String, String>> outputSpreadsheet = new ArrayList<>();

    final Path INPUT_PATH = Path.of(args[0]);

    ArrayList<Map<String, String>> inputSpreadsheet = SpreadsheetUtil.read(INPUT_PATH, false);

    Scanner input = new Scanner(System.in);

    for (Map<String, String> row : inputSpreadsheet) {
      String name = row.get("0");

      JsonObject match = getStoredProduct(storage, name);
      // if it matches to a product that no longer exists, we gotta try again
      boolean invalidMatch = match == null || !SHOP_PRODUCTS.containsKey(match.get("SALE_NAME").getAsString());
      JsonObject product = invalidMatch ? promptInputName(name, row, input) : match;

      if (product == null) continue;

      storage.add(product);
      double supplyPrice = product.get("SUPPLY_PRICE").getAsDouble();

      double salePrice = product.get("SALE_PRICE").getAsDouble();
      double saleToSupplyRatio = product.get("QUANTITY_RATIO").getAsDouble();
      double realSalePrice = salePrice * saleToSupplyRatio;

      double profit = realSalePrice - supplyPrice;
      double profitMargin = (profit / supplyPrice) * 100;

      LinkedHashMap<String, String> productOutputMap = new LinkedHashMap<>();
      productOutputMap.put("Supply Product", product.get("SUPPLY_NAME").getAsString());
      productOutputMap.put("Shop Product", product.get("SALE_NAME").getAsString());
      productOutputMap.put("Supply Price", product.get("SUPPLY_PRICE").getAsString());
      productOutputMap.put("Sale Price", product.get("SALE_PRICE").getAsString());
      productOutputMap.put("Quantity Ratio", product.get("QUANTITY_RATIO").getAsString());
      productOutputMap.put("Profit", Double.toString(profit));
      productOutputMap.put("Profit Margin", Double.toString(profitMargin));
      outputSpreadsheet.add(productOutputMap);

      System.out.println();
    }

    Files.writeString(file, GSON.toJson(storage));
    SpreadsheetUtil.write(INPUT_PATH.getParent().resolve("profits.csv"), outputSpreadsheet);
  }

  private static JsonObject promptInputName(String supplyName, Map<String, String> row, Scanner input) {
    System.out.println("---------------------------\n");

    String matchingName = normalise(supplyName);
    List<String> matchingNames = SHOP_PRODUCTS.keySet()
            .stream()
            .sorted(Comparator.comparingInt(s -> -FuzzySearch.tokenSetRatio(matchingName, normalise(s))))
            .toList();

    String matchingProductsOutput = arrayToIndexedString(matchingNames, 20);

    System.out.printf("""
              The following items could be a match:
              %s
              Enter "s" to skip.
              Enter "n" if the right product is not present.
              Enter the index of the product matching %s:\s""",
            matchingProductsOutput, supplyName);

    String inputString = input.nextLine();
    while (!inputString.matches("\\d+")) {
      if (inputString.equalsIgnoreCase("s")) return null;

      System.out.println(arrayToIndexedString(matchingNames, matchingNames.size()));
      System.out.println("Enter \"s\" to skip");
      System.out.print("Enter the index of the product matching " + supplyName + ": ");
      inputString = input.nextLine();
    }
    int lineNumber = Integer.parseInt(inputString);
    if (lineNumber > matchingNames.size()) {
      System.err.println("Invalid number, skipping!");
      return null;
    }

    String saleName = matchingNames.get(lineNumber);
    double supplyPrice = Double.parseDouble(row.get("2")) / Double.parseDouble(row.get("1"));
    double salePrice = SHOP_PRODUCTS.get(matchingNames.get(lineNumber));

    System.out.println();

    System.out.println("We sell \"" + saleName + "\" for: £" + salePrice);
    System.out.println("We bought \"" + supplyName + "\" for: £" + supplyPrice);
    System.out.print("How many \"" + saleName + "\" would there be in \"" + supplyName + "\": ");

    String supplyRatioInput = input.nextLine();
    if (!supplyRatioInput.matches("\\d+\\.*\\d*")) {
      System.out.println("Invalid number entered, skipping.");
      return null;
    }
    double saleToSupplyRatio = Double.parseDouble(supplyRatioInput);

    JsonObject productStats = new JsonObject();
    productStats.addProperty("SUPPLY_NAME", supplyName);
    productStats.addProperty("SALE_NAME", saleName);
    productStats.addProperty("SUPPLY_PRICE", supplyPrice);
    productStats.addProperty("SALE_PRICE", salePrice);
    productStats.addProperty("QUANTITY_RATIO", saleToSupplyRatio);

    return productStats;
  }

  private static String arrayToIndexedString(List<String> names, int size) {
    return IntStream.range(0, size)
            .mapToObj(i -> size-i-1 + ". " + names.get(size-i-1))
            .collect(Collectors.joining("\n"));
  }

  private static JsonObject getStoredProduct(JsonArray storage, String target) {
    JsonElement matchingElement = storage.asList()
            .stream()
            .filter(name -> name.getAsJsonObject().get("SUPPLY_NAME").getAsString().equals(target))
            .findAny()
            .orElse(null);
    return matchingElement == null ? null : matchingElement.getAsJsonObject();
  }

  private static String normalise(String text) {
    return text.toLowerCase()
            .replaceAll(IGNORED_WORDS_REGEX, "")
            .replaceAll(KILOGRAM_REGEX.pattern(), "")
            .replaceAll(GRAM_REGEX.pattern(), "")

            .replaceAll("[^a-zA-Z ]", " ")
            .replaceAll("\\s+", " ")
            .trim();
  }

  private static Map<String, Double> getProductNamePriceMap() {
    HashMap<String, Double> products = new LinkedHashMap<>();

    int totalItems = NetworkUtil.requestURL(API, Map.of(), ONE_COM_HEADERS)
            .getAsJsonObject("data")
            .get("totalCount").getAsInt();

    HashMap<String, String> params = new HashMap<>(ITEM_DEFAULT_PARAMS);
    for(int i = 0; i < totalItems;) {
      params.put("pageOffset", String.valueOf(i));

      for (JsonElement productElement : NetworkUtil.requestURL(API, params, ONE_COM_HEADERS)
              .getAsJsonObject("data")
              .getAsJsonArray("result")) {
        JsonObject product = productElement.getAsJsonObject();
        List<JsonObject> variants = product
                .get("variants")
                .getAsJsonArray()
                .asList()
                .stream()
                .map(JsonElement::getAsJsonObject)
                .toList();

        String productName = product.get("name").getAsString();
        for (JsonObject variant : variants) {
          JsonObject options = variant.get("optionsValues").getAsJsonObject();
          if (options.isEmpty()) {
            products.put(
                    productName,
                    variant.getAsJsonObject().get("price").getAsDouble()
            );

            continue;
          }
          String variantName = options.get(options.keySet().stream().findFirst().orElseThrow()).getAsString();
          products.put(
                  productName + " [" + variantName + "]",
                  variant.get("price").getAsDouble()
          );
        }

        i++;
      }
    }

    return products;
  }
}