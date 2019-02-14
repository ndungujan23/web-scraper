package com.topcoder.scraper.module.amazon;

import com.gargoylesoftware.htmlunit.WebClient;
import com.topcoder.scraper.Consts;
import com.topcoder.scraper.config.AmazonProperty;
import com.topcoder.scraper.config.CheckItemsDefinitionProperty;
import com.topcoder.scraper.config.MonitorTargetDefinitionProperty;
import com.topcoder.scraper.dao.CheckResultDAO;
import com.topcoder.scraper.dao.NormalDataDAO;
import com.topcoder.scraper.model.Notification;
import com.topcoder.scraper.model.ProductCheckResultDetail;
import com.topcoder.scraper.model.ProductInfo;
import com.topcoder.scraper.model.PurchaseHistory;
import com.topcoder.scraper.model.PurchaseHistoryCheckResultDetail;
import com.topcoder.scraper.module.ChangeDetectionCheckModule;
import com.topcoder.scraper.module.amazon.crawler.AmazonAuthenticationCrawler;
import com.topcoder.scraper.module.amazon.crawler.AmazonAuthenticationCrawlerResult;
import com.topcoder.scraper.module.amazon.crawler.AmazonProductDetailCrawler;
import com.topcoder.scraper.module.amazon.crawler.AmazonProductDetailCrawlerResult;
import com.topcoder.scraper.module.amazon.crawler.AmazonPurchaseHistoryListCrawler;
import com.topcoder.scraper.module.amazon.crawler.AmazonPurchaseHistoryListCrawlerResult;
import com.topcoder.scraper.repository.CheckResultRepository;
import com.topcoder.scraper.repository.NormalDataRepository;
import com.topcoder.scraper.service.WebpageService;
import com.topcoder.scraper.util.CheckUtils;
import java.io.IOException;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Amazon implementation for ChangeDetectionCheckModule
 */
@Component
public class AmazonChangeDetectionCheckModule extends ChangeDetectionCheckModule {

  private static final Logger LOGGER = LoggerFactory.getLogger(AmazonChangeDetectionCheckModule.class);

  private final AmazonProperty property;
  private final MonitorTargetDefinitionProperty monitorTargetDefinitionProperty;
  private final CheckItemsDefinitionProperty checkItemsDefinitionProperty;
  private final WebClient webClient;
  private final WebpageService webpageService;
  private final NormalDataRepository normalDataRepository;
  private final CheckResultRepository checkResultRepository;

  @Autowired
  public AmazonChangeDetectionCheckModule(
    AmazonProperty property,
    MonitorTargetDefinitionProperty monitorTargetDefinitionProperty,
    CheckItemsDefinitionProperty checkItemsDefinitionProperty,
    WebClient webClient,
    WebpageService webpageService,
    CheckResultRepository checkResultRepository,
    NormalDataRepository normalDataRepository) {
    this.property = property;
    this.monitorTargetDefinitionProperty = monitorTargetDefinitionProperty;
    this.checkItemsDefinitionProperty = checkItemsDefinitionProperty;
    this.webClient = webClient;
    this.webpageService = webpageService;
    this.checkResultRepository = checkResultRepository;
    this.normalDataRepository = normalDataRepository;
  }

  @Override
  public String getECName() {
    return "amazon";
  }

  /**
   * Implementation of check method
   */
  @Override
  public void check() throws IOException {
    for (MonitorTargetDefinitionProperty.MonitorTargetCheckPage monitorTargetCheckPage : monitorTargetDefinitionProperty.getCheckPages()) {
      if (monitorTargetCheckPage.getPageName().equalsIgnoreCase(Consts.PURCHASE_HISTORY_LIST_PAGE_NAME)) {
        List<String> usernameList = monitorTargetCheckPage.getCheckTargetKeys();

        String passwordListString = System.getenv(Consts.AMAZON_CHECK_TARGET_KEYS_PASSWORDS);
        if (passwordListString == null) {
          LOGGER.error("Please set environment variable AMAZON_CHECK_TARGET_KEYS_PASSWORDS first");
          throw new RuntimeException("environment variable AMAZON_CHECK_TARGET_KEYS_PASSWORDS not set");
        }
        List<String> passwordList = Arrays.asList(passwordListString.split(","));

        for (int i = 0; i < usernameList.size(); i++) {
          String username = usernameList.get(i);
          String password = passwordList.get(i);

          AmazonAuthenticationCrawler authenticationCrawler = new AmazonAuthenticationCrawler(getECName(), property, webpageService);
          AmazonAuthenticationCrawlerResult loginResult = authenticationCrawler.authenticate(webClient, username, password);
          if (!loginResult.isSuccess()) {
            LOGGER.error(String.format("Failed to login %s with username %s. Skip.", getECName(), username));
            continue;
          }

          AmazonPurchaseHistoryListCrawler purchaseHistoryListCrawler = new AmazonPurchaseHistoryListCrawler(getECName(), property, webpageService);
          AmazonPurchaseHistoryListCrawlerResult crawlerResult = purchaseHistoryListCrawler.fetchPurchaseHistoryList(webClient, null, true);
          processPurchaseHistory(crawlerResult, username);
        }

      } else if (monitorTargetCheckPage.getPageName().equalsIgnoreCase(Consts.PRODUCT_DETAIL_PAGE_NAME)) {
        AmazonProductDetailCrawler crawler = new AmazonProductDetailCrawler(getECName(), property, webpageService);
        for (String productCode : monitorTargetCheckPage.getCheckTargetKeys()) {
          AmazonProductDetailCrawlerResult crawlerResult = crawler.fetchProductInfo(webClient, productCode, true);
          processProductInfo(crawlerResult);
        }

      } else {
        throw new RuntimeException("Unknown monitor target definition " + monitorTargetCheckPage.getPageName());
      }

    }

  }

  /**
   * Process purchase history crawler result
   * @param crawlerResult the crawler result
   * @param pageKey the page key
   */
  private void processPurchaseHistory(AmazonPurchaseHistoryListCrawlerResult crawlerResult, String pageKey) {
    List<PurchaseHistory> purchaseHistoryList = crawlerResult.getPurchaseHistoryList();

    CheckItemsDefinitionProperty.CheckItemsCheckPage checkItemsCheckPage = checkItemsDefinitionProperty.getCheckPages()
      .stream()
      .filter((page) -> page.getPageName().equals(Consts.PURCHASE_HISTORY_LIST_PAGE_NAME))
      .findFirst().orElseThrow(RuntimeException::new);

    NormalDataDAO normalDataDAO = normalDataRepository.findFirstByEcSiteAndPageAndPageKey(getECName(), Consts.PURCHASE_HISTORY_LIST_PAGE_NAME, pageKey);
    if (normalDataDAO == null) {
      // Could not find in database.
      // It's new product.
      LOGGER.warn(
        String.format(
          "Could not find %s (%s) in database, please run change_detection_init first. Skip.",
          getECName(), pageKey));
      return;
    }

    List<PurchaseHistory> dbPurchaseHistoryList = PurchaseHistory.fromJsonToList(normalDataDAO.getNormalData());

    List<PurchaseHistoryCheckResultDetail> results =
      CheckUtils.checkPurchaseHistoryList(checkItemsCheckPage, dbPurchaseHistoryList, purchaseHistoryList);

    boolean passed = results.stream().anyMatch(r -> !r.isOk());

    saveCheckResult(passed, PurchaseHistoryCheckResultDetail.toArrayJson(results), Consts.PURCHASE_HISTORY_LIST_PAGE_NAME, null);

    Notification notification = new Notification(getECName(), Consts.PURCHASE_HISTORY_LIST_PAGE_NAME, pageKey);
    notification.setHtmlPaths(crawlerResult.getHtmlPathList());
    notification.setDetectionTime(new Date());
    webpageService.save("notification", getECName(), notification.toString());
  }

  /**
   * Process product info crawler result
   * @param crawlerResult the crawler result
   */
  private void processProductInfo(AmazonProductDetailCrawlerResult crawlerResult) {
    ProductInfo productInfo = crawlerResult.getProductInfo();

    CheckItemsDefinitionProperty.CheckItemsCheckPage checkItemsCheckPage = checkItemsDefinitionProperty.getCheckPages()
      .stream()
      .filter((page) -> page.getPageName().equalsIgnoreCase(Consts.PRODUCT_DETAIL_PAGE_NAME))
      .findFirst().orElseThrow(RuntimeException::new);

    NormalDataDAO normalDataDAO = normalDataRepository.findFirstByEcSiteAndPageAndPageKey(getECName(), Consts.PRODUCT_DETAIL_PAGE_NAME, productInfo.getCode());

    if (normalDataDAO == null) {
      // Could not find in database.
      // It's new product.
      LOGGER.warn(
        String.format(
          "Could not find %s: %s in database, please run change_detection_init first. Skip.",
          getECName(), productInfo.getCode()));
      return;
    }

    ProductInfo dbProductInfo = ProductInfo.fromJson(normalDataDAO.getNormalData());
    ProductCheckResultDetail result = CheckUtils.checkProductInfo(checkItemsCheckPage, dbProductInfo, productInfo);
    saveCheckResult(result.isOk(), result.toJson(), Consts.PRODUCT_DETAIL_PAGE_NAME, productInfo.getCode());

    Notification notification = new Notification(getECName(), Consts.PRODUCT_DETAIL_PAGE_NAME, productInfo.getCode());
    notification.addHtmlPath(crawlerResult.getHtmlPath());
    notification.setDetectionTime(new Date());
    webpageService.save("notification", getECName(), notification.toString());
  };

  /**
   * Save check result in database
   * @param passed true if result is passed
   * @param checkResultDetail check result detail as string
   * @param page the page name
   * @param pageKey the page key
   */
  private void saveCheckResult(boolean passed, String checkResultDetail, String page, String pageKey) {
    CheckResultDAO dao = checkResultRepository.findFirstByEcSiteAndPageAndPageKey(getECName(), page, pageKey);
    if (dao == null) {
      dao = new CheckResultDAO();
    }

    dao.setEcSite(getECName());
    dao.setCheckResultDetail(checkResultDetail);
    dao.setCheckedAt(new Date());
    dao.setPage(page);
    dao.setPageKey(pageKey);
    dao.setTotalCheckStatus(passed ? "OK" : "NG");
    checkResultRepository.save(dao);
  }
}