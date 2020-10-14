# Website Scraper

## Requirements

- Java 8
- Gradle 3.5
- Spring Boot 1.5.7
- Docker

## Configuration

### configuration file

The app configuration can be changed in `./src/main/resources/application.yaml`

The following variables need to be configured correctly:

- `<EC-Name>.username` the username for &lt;EC-Name&gt;
- `<EC-Name>.password` the password for &lt;EC-Name&gt;

- `spring.datasource.url` mysql url
- `spring.datasource.username` mysql username
- `spring.datasource.password` mysql password

### environment variables

Values could be configured by environment variables also:

- `<EC-Name>_USERNAME`
- `<EC-Name>_PASSWORD`

### arguments

Values could be configured by providing arguments:

For example

`java -D<EC-Name>.username=helloworld -jar build/libs/web-scraper-0.0.1.jar`

### property file

`java -jar build/libs/web-scraper-0.0.1.jar --spring.config.location=file:{somewhere}/application-external.yml`

There are several ways to update configuration, for detail, please check spring boot official docs:
https://docs.spring.io/spring-boot/docs/current/reference/html/boot-features-external-config.html


## Local run from source code

First, run mysql inside docker

`docker-compose up`

In a new terminal, run

`./gradlew bootRun`

To specify site, specify site argument

`./gradlew bootRun -Drun.arguments=--site=<EC-Name>`

If no site is specified, all sites will be run (currently only &lt;EC-Name&gt; is implemented)

## Local run from jar

- `./gradlew build`
- `java -jar build/libs/web-scraper-0.0.1.jar`

To specify site, specify site argument

- `java -jar build/libs/web-scraper-0.0.1.jar --site=<EC-Name>`

If no site is specified, all sites will be run (currently only &lt;EC-Name&gt; is implemented)


## Verification

### File based purchase history 

- change configure, updating &lt;EC-Name&gt; username and password either in configuration file, or through environment
	variables, or through other way
- run though gradle, or jar file
- check `./<EC-Name>` folder, `login-*.html` are initial pages after login and `history-*.json` are purchase histories.
- to verify incremental save, edit history json file, remove one order. Then rerun application, there should be a new json file containing removed order. 

### Mysql purchase history

- change configure, updating mysql connect information, updating &lt;EC-Name&gt; username and password
    changes could be made either in configuration file, or through environment
	variables, or through other way
- run though gradle, or jar file
- check `./<EC-Name>` folder, `login-*.html` are initial pages after login and `purchase-history-*.json` are purchase histories pages.
- to verify incremental save, delete last record in mysql. Then rerun application, there should be one new row containing removed order. 

To connect to mysql in docker, please specify host as `0.0.0.0`, see below:

> `mysql --host 0.0.0.0 --port 3306 --user root --password`
