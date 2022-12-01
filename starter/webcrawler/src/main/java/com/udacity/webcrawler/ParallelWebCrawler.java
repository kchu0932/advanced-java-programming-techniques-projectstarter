package com.udacity.webcrawler;

import com.udacity.webcrawler.json.CrawlResult;
import com.udacity.webcrawler.parser.PageParser;
import com.udacity.webcrawler.parser.PageParserFactory;

import javax.inject.Inject;
import javax.inject.Provider;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentSkipListSet;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * A concrete implementation of {@link WebCrawler} that runs multiple threads on a
 * {@link ForkJoinPool} to fetch and process multiple web pages in parallel.
 */
final class ParallelWebCrawler implements WebCrawler {
  private final Clock clock;
  private final Duration timeout;
  private final int popularWordCount;
  private final ForkJoinPool pool;


  private final int maxDepth;
  private final List<Pattern> ignoredUrls;

  @Inject
  ParallelWebCrawler(
      Clock clock,
      @Timeout Duration timeout,
      @PopularWordCount int popularWordCount,
      @TargetParallelism int threadCount,

      @MaxDepth int maxDepth,
      @IgnoredUrls List<Pattern> ignoredUrls){
    this.clock = clock;
    this.timeout = timeout;
    this.popularWordCount = popularWordCount;
    this.pool = new ForkJoinPool(Math.min(threadCount, getMaxParallelism()));

    this.maxDepth = maxDepth;
    this.ignoredUrls = ignoredUrls;
  }

//  @Override
  @Inject PageParserFactory parserFactory;
  public CrawlResult crawl(List<String> startingUrls) {
    Instant deadline = clock.instant().plus(timeout);
    Map<String, Integer> counts = new HashMap<>();
    Set<String> visitedUrls = new HashSet<>();
    for (String url:
         startingUrls) {
      pool.submit(() -> {
        crawlInternal(url, deadline, maxDepth, counts, visitedUrls);
      });

    }
    pool.shutdown();
    try {pool.awaitTermination(timeout.getSeconds(), TimeUnit.SECONDS);}
    catch (Exception e){};

    if (counts.isEmpty()) {
      return new CrawlResult.Builder()
              .setWordCounts(counts)
              .setUrlsVisited(visitedUrls.size())
              .build();
    }

    return new CrawlResult.Builder()
            .setWordCounts(WordCounts.sort(counts, popularWordCount))
            .setUrlsVisited(visitedUrls.size())
            .build();
  }

  private void crawlInternal(
          String url,
          Instant deadline,
          int maxDepth,
          Map<String, Integer> counts,
          Set<String> visitedUrls) {
    if (maxDepth == 0 || clock.instant().isAfter(deadline)) {
      return;
    }
    for (Pattern pattern : ignoredUrls) {
      if (pattern.matcher(url).matches()) {
        return;
      }
    }
    if (visitedUrls.contains(url)) {
      return;
    }
    visitedUrls.add(url);
    PageParser.Result result = parserFactory.get(url).parse();
    for (Map.Entry<String, Integer> e : result.getWordCounts().entrySet()) {
      if (counts.containsKey(e.getKey())) {
        counts.put(e.getKey(), e.getValue() + counts.get(e.getKey()));
      } else {
        counts.put(e.getKey(), e.getValue());
      }
    }
    for (String link : result.getLinks()) {
      pool.submit(() -> crawlInternal(link, deadline, maxDepth - 1, counts, visitedUrls));
    }
  }


  @Override
  public int getMaxParallelism() {
    return Runtime.getRuntime().availableProcessors();
  }
}
