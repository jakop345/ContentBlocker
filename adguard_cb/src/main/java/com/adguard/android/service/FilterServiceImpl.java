/**
 This file is part of Adguard Content Blocker (https://github.com/AdguardTeam/ContentBlocker).
 Copyright © 2016 Performix LLC. All rights reserved.

 Adguard Content Blocker is free software: you can redistribute it and/or modify
 it under the terms of the GNU General Public License as published by the
 Free Software Foundation, either version 3 of the License, or (at your option)
 any later version.

 Adguard Content Blocker is distributed in the hope that it will be useful,
 but WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY
 or FITNESS FOR A PARTICULAR PURPOSE. See the GNU General Public License for more details.

 You should have received a copy of the GNU General Public License along with
 Adguard Content Blocker.  If not, see <http://www.gnu.org/licenses/>.
 */
package com.adguard.android.service;

import android.app.Activity;
import android.app.ProgressDialog;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;

import com.adguard.android.contentblocker.R;
import com.adguard.android.ServiceLocator;
import com.adguard.android.db.FilterListDao;
import com.adguard.android.db.FilterListDaoImpl;
import com.adguard.android.db.FilterRuleDao;
import com.adguard.android.db.FilterRuleDaoImpl;
import com.adguard.android.contentblocker.ServiceApiClient;
import com.adguard.android.model.FilterList;
import com.adguard.commons.NetworkUtils;
import com.adguard.commons.concurrent.DispatcherThreadPool;
import com.adguard.commons.io.IoUtils;
import com.adguard.commons.InternetUtils;
import com.adguard.commons.web.UrlUtils;

import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.TimeUnit;

/**
 * Filter service implementation.
 */
public class FilterServiceImpl extends BaseUiService implements FilterService {
    private static final Logger LOG = LoggerFactory.getLogger(FilterServiceImpl.class);

    private final Context context;
    private final FilterListDao filterListDao;
    private final FilterRuleDao filterRuleDao;
    private final PreferencesService preferencesService;

    private static final String FILTERS_UPDATE_JOB_NAME = "filters update job";
    private static final int FILTERS_UPDATE_INITIAL_DELAY = 60 * 60; // 1 hour
    private static final int FILTERS_UPDATE_PERIOD = 60 * 60; // 1 hour
    private static final int UPDATE_INVALIDATE_PERIOD = 4 * 24 * 60 * 60; // 4 days

    public static final int SHOW_USEFUL_ADS_FILTER_ID = 10;
    private static final int SOCIAL_MEDIA_WIDGETS_FILTER_ID = 4;
    private static final int SPYWARE_FILTER_ID = 3;

    private static final String FILTERS_UPDATE_QUEUE = "filters-update-queue";
    private int cachedFilterRuleCount = 0;

    /**
     * Creates an instance of AdguardService
     *
     * @param context Context
     */
    public FilterServiceImpl(Context context) {
        LOG.info("Creating AdguardService instance for {}", context);
        this.context = context;
        filterListDao = new FilterListDaoImpl(context);
        filterRuleDao = new FilterRuleDaoImpl(context);
        preferencesService = ServiceLocator.getInstance(context).getPreferencesService();
    }

    public static void enableYandexContentBlocker(Context context, boolean enable) {
        Intent intent = new Intent();
        if (enable) {
            intent.setAction("com.yandex.browser.contentFilter.INSTALLED");
        } else {
            intent.setAction("com.yandex.browser.contentFilter.UNINSTALLED");
        }
        intent.setPackage(context.getPackageName());
        context.startService(intent);
    }

    public static void enableSamsungContentBlocker(Context context) {
        Intent intent = new Intent();
        intent.setAction("com.samsung.android.sbrowser.contentBlocker.ACTION_UPDATE");
        intent.setData(Uri.parse("package:com.adguard.android.contentblocker"));
        context.sendBroadcast(intent);
    }

    @Override
    public void checkFiltersUpdates(Activity activity) {
        LOG.info("Start manual filters updates check");
        ServiceLocator.getInstance(activity.getApplicationContext()).getPreferencesService().setLastUpdateCheck(new Date().getTime());

        ProgressDialog progressDialog = showProgressDialog(activity, R.string.checkUpdatesProgressDialogTitle, R.string.checkUpdatesProgressDialogMessage);
        DispatcherThreadPool.getInstance().submit(FILTERS_UPDATE_QUEUE, new CheckUpdatesTask(activity, progressDialog));
        LOG.info("Submitted filters update task");
    }

    @Override
    public List<FilterList> getFilters() {
        return filterListDao.selectFilterLists();
    }

    @Override
    public int getFilterListCount() {
        return filterListDao.getFilterListCount();
    }

    @Override
    public int getEnabledFilterListCount() {
        return filterListDao.getEnabledFilterListCount();
    }

    @Override
    public int getFilterRuleCount() {
        if (cachedFilterRuleCount == 0) {
            cachedFilterRuleCount = preferencesService.getFilterRuleCount();
        }
        return cachedFilterRuleCount;
    }

    @Override
    public List<FilterList> checkFilterUpdates() {
        return checkOutdatedFilterUpdates(true);
    }

    @Override
    public void scheduleFiltersUpdate() {
        ServiceLocator.getInstance(context).getJobService().scheduleAtFixedRate(FILTERS_UPDATE_JOB_NAME,
                new Runnable() {
                    @Override
                    public void run() {
                        checkOutdatedFilterUpdates(false);
                    }
                }, FILTERS_UPDATE_INITIAL_DELAY, FILTERS_UPDATE_PERIOD, TimeUnit.SECONDS);
    }

    @Override
    public void updateFilterEnabled(FilterList filter, boolean enabled) {
        filter.setEnabled(enabled);
        filterListDao.updateFilterEnabled(filter, enabled);
    }

    @Override
    public Set<String> getWhiteList() {
        return preferencesService.getWhiteList();
    }

    @Override
    public void addToWhitelist(String item) {
        preferencesService.addToWhitelist(item);
    }

    @Override
    public void clearWhiteList() {
        preferencesService.clearWhiteList();
    }

    @Override
    public void removeWhiteListItem(String item) {
        preferencesService.removeWhiteListItem(item);
    }

    @Override
    public Set<String> getUserRules() {
        return preferencesService.getUserRules();
    }

    @Override
    public void addUserRuleItem(String item) {
        preferencesService.addUserRuleItem(item);
    }

    @Override
    public void removeUserRuleItem(String item) {
        preferencesService.removeUserRuleItem(item);
    }

    @Override
    public void clearUserRules() {
        preferencesService.clearUserRules();
    }

    @Override
    public void importUserRulesFromUrl(Activity activity, String url) {
        LOG.info("Start import user rules from {}", url);

        ProgressDialog progressDialog = showProgressDialog(activity, R.string.importUserRulesProgressDialogTitle, R.string.importUserRulesProgressDialogMessage);
        DispatcherThreadPool.getInstance().submit(new ImportUserRulesTask(activity, progressDialog, url));
        LOG.info("Submitted import user rules task");
    }

    @Override
    public List<String> getAllEnabledRules(boolean useCosmetics) {
        List<Integer> filterIds = getEnabledFilterIds();
        return filterRuleDao.selectRuleTexts(filterIds, useCosmetics);
    }

    @Override
    public List<Integer> getEnabledFilterIds() {
        List<Integer> filterIds = new ArrayList<>();
        for (FilterList filter : getEnabledFilters()) {
            filterIds.add(filter.getFilterId());
        }
        return filterIds;
    }

    @Override
    public boolean isShowUsefulAds() {
        final FilterList filter = filterListDao.selectFilterList(SHOW_USEFUL_ADS_FILTER_ID);
        return filter != null && filter.isEnabled();
    }

    @Override
    public void setShowUsefulAds(boolean value) {
        final FilterList filter = filterListDao.selectFilterList(SHOW_USEFUL_ADS_FILTER_ID);
        if (filter != null) {
            updateFilterEnabled(filter, value);
        }
    }

    @Override
    public boolean isSocialMediaWidgetsFilterEnabled() {
        final FilterList filter = filterListDao.selectFilterList(SOCIAL_MEDIA_WIDGETS_FILTER_ID);
        return filter != null && filter.isEnabled();
    }

    @Override
    public void setSocialMediaWidgetsFilterEnabled(boolean value) {
        final FilterList filter = filterListDao.selectFilterList(SOCIAL_MEDIA_WIDGETS_FILTER_ID);
        if (filter != null) {
            updateFilterEnabled(filter, value);
        }
    }

    @Override
    public boolean isSpywareFilterEnabled() {
        final FilterList filter = filterListDao.selectFilterList(SPYWARE_FILTER_ID);
        return filter != null && filter.isEnabled();
    }

    @Override
    public void applyNewSettings() {
        // TODO fix this crutch
        setShowUsefulAds(preferencesService.isShowUsefulAds());
        List<String> allEnabledRules = getAllEnabledRules(true);
        cachedFilterRuleCount = allEnabledRules.size();
        try {
            LOG.info("Saving {} filters...", cachedFilterRuleCount);
            FileUtils.writeLines(new File(context.getFilesDir().getAbsolutePath() + "/filters.txt"), allEnabledRules);
            preferencesService.setFilterRuleCount(cachedFilterRuleCount);
            enableSamsungContentBlocker(context);
            enableYandexContentBlocker(context, true);
        } catch (IOException e) {
            LOG.warn("Unable to save filters to file!!!", e);
        }
    }

    @Override
    public void setSpywareFilterEnabled(boolean value) {
        final FilterList filter = filterListDao.selectFilterList(SPYWARE_FILTER_ID);
        if (filter != null) {
            updateFilterEnabled(filter, value);
        }
    }

    /**
     * Updates filters without updates for some time.
     *
     * @param force If true - updates not only over wifi
     * @return List of updated filters or null if something gone wrong
     */
    private List<FilterList> checkOutdatedFilterUpdates(boolean force) {

        if (!force) {

            if (!NetworkUtils.isNetworkAvailable(context) || !InternetUtils.isInternetAvailable()) {
                LOG.info("checkOutdatedFilterUpdates: internet is not available, doing nothing.");
                return new ArrayList<>();
            }

            if (preferencesService.isUpdateOverWifiOnly() && !NetworkUtils.isConnectionWifi(context)) {
                LOG.info("checkOutdatedFilterUpdates: Updates permitted over Wi-Fi only, doing nothing.");
                return new ArrayList<>();
            }

            boolean updateFilters = preferencesService.isAutoUpdateFilters();
            if (!updateFilters) {
                LOG.info("Filters auto-update is disabled, doing nothing");
                return new ArrayList<>();
            }
        }

        List<FilterList> filtersToUpdate = new ArrayList<>();
        long timeFromUpdate = new Date().getTime() - UPDATE_INVALIDATE_PERIOD;
        for (FilterList filter : getEnabledFilters()) {

            if (force || (filter.getLastTimeDownloaded() == null) || (filter.getLastTimeDownloaded().getTime() - timeFromUpdate < 0)) {
                filtersToUpdate.add(filter);
            }
        }

        return checkFilterUpdates(filtersToUpdate, force);
    }

    private List<FilterList> checkFilterUpdates(List<FilterList> filters, boolean force) {
        LOG.info("Start checking filters updates for {} outdated filters. Forced={}", filters.size(), force);

        if (CollectionUtils.isEmpty(filters)) {
            LOG.info("Empty filters list, doing nothing");
            return new ArrayList<>();
        }

        preferencesService.setLastUpdateCheck(new Date().getTime());

        try {
            final List<FilterList> updated = ServiceApiClient.downloadFilterVersions(context, filters);
            if (updated == null) {
                LOG.warn("Cannot download filter updates.");
                return null;
            }

            Map<Integer, FilterList> map = new HashMap<>();
            for (FilterList filter : updated) {
                map.put(filter.getFilterId(), filter);
            }

            for (FilterList current : filters) {
                final int filterId = current.getFilterId();
                if (!map.containsKey(filterId)) {
                    continue;
                }

                FilterList update = map.get(filterId);
                if (update.getVersion().compareTo(current.getVersion()) > 0) {
                    current.setVersion(update.getVersion().toString());
                    current.setLastTimeDownloaded(new Date());
                    current.setTimeUpdated(update.getTimeUpdated());
                    map.put(filterId, current);

                    LOG.info("Updating filter:" + current.getFilterId());
                    updateFilter(current);
                    LOG.info("Updating rules for filter:" + current.getFilterId());
                    updateFilterRules(filterId);
                } else {
                    map.remove(filterId);
                }
            }

            LOG.info("Finished checking filters updates.");

            return new ArrayList<>(map.values());
        } catch (IOException e) {
            LOG.error("Error checking filter updates:\r\n", e);
        } catch (Exception e) {
            LOG.error("Error parsing server response:\r\n", e);
        }

        return null;
    }

    private List<FilterList> getEnabledFilters() {
        List<FilterList> enabledFilters = new ArrayList<>();

        List<FilterList> filters = getFilters();
        for (FilterList filter : filters) {
            if (filter.isEnabled()) {
                enabledFilters.add(filter);
            }
        }

        LOG.info("Found {} enabled filters", enabledFilters.size());

        return enabledFilters;
    }

    private void updateFilterRules(int filterId) throws IOException {
        final List<String> rules = ServiceApiClient.downloadFilterRules(context, filterId, null);
        filterRuleDao.setFilterRules(filterId, rules);
    }

    private void updateFilter(FilterList current) {
        filterListDao.updateFilter(current);
    }

    /**
     * Task for importing user rules
     */
    private class ImportUserRulesTask extends LongRunningTask {

        private Activity activity;
        private String url;

        public ImportUserRulesTask(Activity activity, ProgressDialog progressDialog, String url) {
            super(progressDialog);
            this.activity = activity;
            this.url = url;
        }

        @Override
        protected void processTask() throws Exception {
            LOG.info("Downloading user rules from {}", url);
            String file = loadFromFile(url);
            final String download = file != null ? file : UrlUtils.downloadString(url);
            final String[] rules = StringUtils.split(download, "\n");

            if (rules == null || rules.length < 1) {
                LOG.error("Error downloading user rules from {}", url);
                onError();
                return;
            }

            LOG.info("{} user rules downloaded from {}", rules.length);

            final List<String> rulesList = new ArrayList<>(rules.length);
            for (String rule : rules) {
                final String trimmedRule = rule.trim();
                if (StringUtils.isNotBlank(trimmedRule) && trimmedRule.length() < 8000) {
                    rulesList.add(trimmedRule);
                }
            }

            if (rulesList.size() < 1) {
                LOG.error("Invalid user rules from {}", url);
                onError();
                return;
            }

            preferencesService.addUserRuleItems(rulesList);
            LOG.info("User rules added successfully.");

            ServiceLocator.getInstance(activity.getApplicationContext()).getFilterService().applyNewSettings();

            String message = activity.getString(R.string.importUserRulesSuccessResultMessage).replace("{0}", String.valueOf(rulesList.size()));
            showToast(activity, message);
        }

        private void onError() {
            String message = activity.getString(R.string.importUserRulesErrorResultMessage);
            showToast(activity, message);
        }

        private String loadFromFile(String url) throws Exception {
            File f = new File(url);
            if (f.exists() && f.isFile() && f.canRead()) {
                FileInputStream fis = new FileInputStream(f);
                byte[] buf = IoUtils.readToEnd(fis);
                IOUtils.closeQuietly(fis);
                return new String(buf);
            }
            return null;
        }
    }

    /**
     * Task for checking updates
     */
    private class CheckUpdatesTask extends LongRunningTask {

        private Activity activity;

        public CheckUpdatesTask(Activity activity, ProgressDialog progressDialog) {
            super(progressDialog);
            this.activity = activity;
        }

        @Override
        protected void processTask() throws Exception {
            final List<FilterList> filters = ServiceLocator.getInstance(context).getFilterService().checkFilterUpdates();
            if (filters == null) {
                String message = activity.getString(R.string.checkUpdatesErrorResultMessage);
                showToast(activity, message);
            } else {
                if (filters.size() == 0) {
                    String message = activity.getString(R.string.checkUpdatesZeroResultMessage);
                    showToast(activity, message);
                } else {
                    if (filters.size() == 1) {
                        String message = activity.getString(R.string.checkUpdatesOneResultMessage).replace("{0}", parseFilterNames(filters));
                        showToast(activity, message);
                    } else {
                        String message = activity.getString(R.string.checkUpdatesManyResultMessage)
                                .replace("{0}", Integer.toString(filters.size()))
                                .replace("{1}", parseFilterNames(filters));
                        showToast(activity, message);
                    }
                    applyNewSettings();
                }
                preferencesService.setLastUpdateCheck(System.currentTimeMillis());
            }
        }

        private String parseFilterNames(List<FilterList> filters) {
            StringBuilder sb = new StringBuilder();
            for (FilterList filter : filters) {
                sb.append(" ");
                sb.append(filter.getName());
                sb.append(",");
            }

            if (sb.indexOf(",") > 0) {
                sb.deleteCharAt(sb.length() - 1);
            }

            return sb.toString();
        }
    }
}
