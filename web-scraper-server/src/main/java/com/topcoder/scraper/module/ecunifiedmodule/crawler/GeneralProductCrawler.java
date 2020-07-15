package com.topcoder.scraper.module.ecunifiedmodule.crawler;

import java.io.File;
import java.io.IOException;
import java.util.Properties;

import org.apache.commons.io.FileUtils;
import org.apache.commons.lang3.StringUtils;
import org.codehaus.groovy.control.CompilerConfiguration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.topcoder.common.model.ProductInfo;
import com.topcoder.common.traffic.TrafficWebClient;
import com.topcoder.scraper.Consts;
import com.topcoder.scraper.lib.navpage.NavigableProductDetailPage;
import com.topcoder.scraper.lib.navpage.NavigableProductListPage;
import com.topcoder.scraper.service.WebpageService;

import groovy.lang.Binding;
import groovy.lang.Closure;
import groovy.lang.GroovyShell;
import groovy.lang.Script;
import lombok.Getter;
import lombok.Setter;

/**
 * General implementation of PurchaseHistoryModule
 */
public class GeneralProductCrawler {

  private static final Logger LOGGER = LoggerFactory.getLogger(GeneralProductCrawler.class);

  private Binding               scriptBinding;
  private CompilerConfiguration scriptConfig;
  private GroovyShell           scriptShell;
  private String                scriptText = "";

  private String savedPath;

  @Getter@Setter private WebpageService   webpageService;
  @Getter@Setter private TrafficWebClient webClient;
  @Getter@Setter private String           siteName;

  @Getter@Setter private String           productCode;
  @Getter@Setter private ProductInfo      productInfo;
  @Getter@Setter private String           searchWord;
  @Getter@Setter private NavigableProductDetailPage detailPage;
  @Getter@Setter private NavigableProductListPage   listPage;

  public GeneralProductCrawler(String siteName, WebpageService webpageService) {
    LOGGER.debug("[constructor] in");

    this.siteName       = siteName;
    this.webpageService = webpageService;

    // setup script
    Properties configProps = new Properties();
    configProps.setProperty("groovy.script.base", this.getScriptSupportClassName());
    this.scriptConfig  = new org.codehaus.groovy.control.CompilerConfiguration(configProps);
    this.scriptBinding = new Binding();
    this.scriptShell = new GroovyShell(this.scriptBinding, this.scriptConfig);

  }

  private String getScriptPath(String scriptFile) {
    LOGGER.debug("[getScriptPath] in");

    String scriptPath = System.getenv(Consts.SCRAPING_SCRIPT_PATH);
    if (StringUtils.isEmpty(scriptPath)) {
      scriptPath = System.getProperty("user.dir") + "/scripts/scraping";
    }
    scriptPath  += "/unified/" + scriptFile;

    LOGGER.info("scriptPath: " + scriptPath);
    return scriptPath;
  }

  private String getScriptText(String scriptPath) {
    LOGGER.debug("[getScriptText] in");

    try {
      return FileUtils.readFileToString(new File(scriptPath), "utf-8");
    } catch (IOException e) {
      LOGGER.info("Could not read script file: " + scriptPath);
      return null;
    }
  }

  private String getScriptSupportClassName() {
    return GeneralProductCrawlerScriptSupport.class.getName();
  }

  private String executeScript(String scriptFile) {
    LOGGER.debug("[executeScript] in");

    // get script srouce
    String scriptPath = this.getScriptPath(scriptFile);
    this.scriptText   = this.getScriptText(scriptPath);

    // execute script
    Script script = this.scriptShell.parse(this.scriptText);
    script.invokeMethod("setCrawler", this);
    String resStr = (String)script.run();
    return resStr;
  }

  public GeneralProductCrawlerResult fetchProductInfo(TrafficWebClient webClient, String productCode) {
    LOGGER.debug("[fetchProductInfo] in");

    this.webClient  = webClient;
    this.detailPage = new NavigableProductDetailPage(this.webClient);

    this.productCode = productCode;
    this.productInfo = new ProductInfo();
    this.productInfo.setCode(productCode);
    this.detailPage.setProductInfo(this.productInfo);

    // binding variables for scraping script
    this.scriptBinding.setProperty("productCode", this.productCode);

    // execute script
    this.executeScript(this.siteName + "-product-detail.groovy");

    String savedPath = this.webpageService.save(this.siteName + "-product-detail", this.siteName,  this.detailPage.getPage().getWebResponse().getContentAsString(), true);
    if (savedPath != null) {
      this.savedPath = savedPath;
    }

    return new GeneralProductCrawlerResult(this.productInfo, this.savedPath);
  }

  public String searchProduct(TrafficWebClient webClient, String searchWord) throws IOException {
    LOGGER.debug("[searchProduct] in");

    this.webClient = webClient;
    this.listPage  = new NavigableProductListPage(this.webClient);

    this.searchWord  = searchWord;
    this.productCode = null;

    // execute script
    this.executeScript(this.siteName + "-search-product.groovy");

    return this.productCode;
  }

  public String eachProducts(Closure<String> closure) {

    //10 times try
    for(int index = 0; index < Consts.SEARCH_PRODUCT_TRIAL_COUNT ; index++) {
      this.productCode = closure.call(index);

      if (StringUtils.isNotEmpty(this.productCode)) {
        return this.productCode;
      }
    }

    LOGGER.info(String.format("Could not find product with search word = %s",searchWord));
    return null;
  }
}
