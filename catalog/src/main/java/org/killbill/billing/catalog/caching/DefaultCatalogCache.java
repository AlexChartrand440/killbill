/*
 * Copyright 2014-2018 Groupon, Inc
 * Copyright 2014-2018 The Billing Project, LLC
 *
 * The Billing Project licenses this file to you under the Apache License, version 2.0
 * (the "License"); you may not use this file except in compliance with the
 * License.  You may obtain a copy of the License at:
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.  See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package org.killbill.billing.catalog.caching;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import javax.inject.Inject;

import org.joda.time.DateTime;
import org.killbill.billing.ErrorCode;
import org.killbill.billing.ObjectType;
import org.killbill.billing.callcontext.InternalTenantContext;
import org.killbill.billing.catalog.DefaultVersionedCatalog;
import org.killbill.billing.catalog.StandaloneCatalog;
import org.killbill.billing.catalog.StandaloneCatalogWithPriceOverride;
import org.killbill.billing.catalog.api.Catalog;
import org.killbill.billing.catalog.api.CatalogApiException;
import org.killbill.billing.catalog.api.StaticCatalog;
import org.killbill.billing.catalog.io.VersionedCatalogLoader;
import org.killbill.billing.catalog.override.PriceOverride;
import org.killbill.billing.catalog.plugin.VersionedCatalogMapper;
import org.killbill.billing.catalog.plugin.api.CatalogPluginApi;
import org.killbill.billing.catalog.plugin.api.VersionedPluginCatalog;
import org.killbill.billing.osgi.api.OSGIServiceRegistration;
import org.killbill.billing.payment.api.PluginProperty;
import org.killbill.billing.util.cache.Cachable.CacheType;
import org.killbill.billing.util.cache.CacheController;
import org.killbill.billing.util.cache.CacheControllerDispatcher;
import org.killbill.billing.util.cache.CacheLoaderArgument;
import org.killbill.billing.util.cache.TenantCatalogCacheLoader.LoaderCallback;
import org.killbill.billing.util.callcontext.InternalCallContextFactory;
import org.killbill.billing.util.callcontext.TenantContext;
import org.killbill.clock.Clock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;

public class DefaultCatalogCache implements CatalogCache {

    private final Logger logger = LoggerFactory.getLogger(DefaultCatalogCache.class);

    private final CacheController<Long, DefaultVersionedCatalog> cacheController;
    private final VersionedCatalogLoader loader;
    private final CacheLoaderArgument cacheLoaderArgumentWithTemplateFiltering;
    private final CacheLoaderArgument cacheLoaderArgument;
    private final OSGIServiceRegistration<CatalogPluginApi> pluginRegistry;
    private final VersionedCatalogMapper versionedCatalogMapper;
    private final PriceOverride priceOverride;
    private final InternalCallContextFactory internalCallContextFactory;
    private final Clock clock;
    private List<StaticCatalog> defaultCatalog;

    @Inject
    public DefaultCatalogCache(final OSGIServiceRegistration<CatalogPluginApi> pluginRegistry,
                               final VersionedCatalogMapper versionedCatalogMapper,
                               final CacheControllerDispatcher cacheControllerDispatcher,
                               final VersionedCatalogLoader loader,
                               final PriceOverride priceOverride,
                               final Clock clock,
                               final InternalCallContextFactory internalCallContextFactory) {
        this.pluginRegistry = pluginRegistry;
        this.versionedCatalogMapper = versionedCatalogMapper;
        this.cacheController = cacheControllerDispatcher.getCacheController(CacheType.TENANT_CATALOG);
        this.loader = loader;
        this.priceOverride = priceOverride;
        this.clock = clock;
        this.internalCallContextFactory = internalCallContextFactory;
        this.cacheLoaderArgumentWithTemplateFiltering = initializeCacheLoaderArgument(true);
        this.cacheLoaderArgument = initializeCacheLoaderArgument(false);
        setDefaultCatalog();
    }

    @Override
    public void loadDefaultCatalog(final String url) throws CatalogApiException {
        if (url != null) {
            defaultCatalog = loader.loadDefaultCatalog(url);
        }
    }

    @Override
    public List<StaticCatalog> getCatalog(final boolean useDefaultCatalog, final boolean filterTemplateCatalog, final boolean internalUse, final InternalTenantContext tenantContext) throws CatalogApiException {

        //
        // This is used by Kill Bill services (subscription/invoice/... creation/change)
        //
        // The goal is to ensure that on such operations any catalog plugin would also receive a TenantContext that contains both tenantId AND accountID (and could therefore run some optimization to return
        // specific pieces of catalog for certain account). In such a scenario plugin would have to make sure that Kill Bill does not cache the catalog (since this is only cached at the tenant level)
        //
        if (internalUse) {
            Preconditions.checkState(tenantContext.getAccountRecordId() != null, "Unexpected null accountRecordId in context issued from internal Kill Bill service");
        }

        final List<StaticCatalog> pluginVersionedCatalog = getCatalogFromPlugins(tenantContext);
        if (pluginVersionedCatalog != null) {
            return pluginVersionedCatalog;
        }

        if (InternalCallContextFactory.INTERNAL_TENANT_RECORD_ID.equals(tenantContext.getTenantRecordId())) {
            return useDefaultCatalog ? defaultCatalog : null;
        }
        // The cache loader might choke on some bad xml -- unlikely since we check its validity prior storing it,
        // but to be on the safe side;;
        try {
            final DefaultVersionedCatalog versionnedCatalog = cacheController.get(tenantContext.getTenantRecordId(),
                                                                              filterTemplateCatalog ? cacheLoaderArgumentWithTemplateFiltering : cacheLoaderArgument);
            List<StaticCatalog> tenantCatalog = versionnedCatalog != null ? versionnedCatalog.getVersions() : null;

            // It means we are using a default catalog in a multi-tenant deployment, that does not really match a real use case, but we want to support it
            // for test purpose.
            if (useDefaultCatalog && tenantCatalog == null) {
                tenantCatalog = new ArrayList<>();
                for (final StaticCatalog cur : defaultCatalog) {
                    final StandaloneCatalogWithPriceOverride curWithOverride = new StandaloneCatalogWithPriceOverride(cur, priceOverride, tenantContext.getTenantRecordId(), internalCallContextFactory);
                    tenantCatalog.add(curWithOverride);
                }

                final DefaultVersionedCatalog cachedCatalog = new DefaultVersionedCatalog(clock, tenantCatalog);
                cacheController.putIfAbsent(tenantContext.getTenantRecordId(), cachedCatalog);
            }

            if (tenantCatalog != null) {
                initializeCatalog(tenantCatalog);
            }

            return tenantCatalog;
        } catch (final IllegalStateException e) {
            throw new CatalogApiException(ErrorCode.CAT_INVALID_FOR_TENANT, tenantContext.getTenantRecordId());
        }
    }

    @Override
    public void clearCatalog(final InternalTenantContext tenantContext) {
        if (!InternalCallContextFactory.INTERNAL_TENANT_RECORD_ID.equals(tenantContext.getTenantRecordId())) {
            cacheController.remove(tenantContext.getTenantRecordId());
        }
    }

    private List<StaticCatalog> getCatalogFromPlugins(final InternalTenantContext internalTenantContext) throws CatalogApiException {
        final TenantContext tenantContext = internalCallContextFactory.createTenantContext(internalTenantContext);
        final Set<String> allServices = pluginRegistry.getAllServices();
        for (final String service : allServices) {
            final CatalogPluginApi plugin = pluginRegistry.getServiceForName(service);

            //
            // Beware plugin implementors:  latestCatalogUpdatedDate returned by the plugin should also match the effectiveDate of the VersionedCatalog.
            //
            // However, this is the plugin choice to return one, or many catalog versions (StandaloneCatalog), Kill Bill catalog module does not care.
            // As a guideline, if plugin keeps seeing new Plans/Products, this can all fit into the same version; however if there is a true versioning
            // (e.g deleted Plans...), then multiple versions must be returned.
            //
            final DateTime latestCatalogUpdatedDate = plugin.getLatestCatalogVersion(ImmutableList.<PluginProperty>of(), tenantContext);
            // A null latestCatalogUpdatedDate bypasses caching, by fetching full catalog from plugin below (compatibility mode with 0.18.x or non optimized plugin api mode)
            final boolean cacheable = latestCatalogUpdatedDate != null;
            if (cacheable) {
                final DefaultVersionedCatalog versionnedCatalog = cacheController.get(internalTenantContext.getTenantRecordId(), cacheLoaderArgument);
                final List<StaticCatalog> tenantCatalog = versionnedCatalog != null ? versionnedCatalog.getVersions() : null;
                if (tenantCatalog != null) {
                    initializeCatalog(tenantCatalog);
                    if (tenantCatalog.get(tenantCatalog.size() - 1).getEffectiveDate().compareTo(latestCatalogUpdatedDate.toDate()) == 0) {
                        // Current cached version matches the one from the plugin
                        return tenantCatalog;
                    }
                }
            }

            final VersionedPluginCatalog pluginCatalog = plugin.getVersionedPluginCatalog(ImmutableList.<PluginProperty>of(), tenantContext);
            // First plugin that gets something (for that tenant) returns it
            if (pluginCatalog != null) {
                // The log entry is only interesting if there are multiple plugins
                if (allServices.size() > 1) {
                    logger.info("Returning catalog from plugin {} on tenant {} ", service, internalTenantContext.getTenantRecordId());
                }

                final List<StaticCatalog> resolvedPluginCatalog = versionedCatalogMapper.toVersionedCatalog(pluginCatalog, internalTenantContext).getVersions();

                // Always clear the cache for safety
                cacheController.remove(internalTenantContext.getTenantRecordId());
                if (cacheable) {
                    final DefaultVersionedCatalog cachedCatalog = new DefaultVersionedCatalog(clock, resolvedPluginCatalog);
                    cacheController.putIfAbsent(internalTenantContext.getTenantRecordId(), cachedCatalog);
                }

                return resolvedPluginCatalog;
            }
        }
        return null;
    }

    private void initializeCatalog(final List<StaticCatalog> tenantCatalog) {
        for (final StaticCatalog cur : tenantCatalog) {
            if (cur instanceof StandaloneCatalogWithPriceOverride) {
                ((StandaloneCatalogWithPriceOverride) cur).initialize((StandaloneCatalog) cur, priceOverride, internalCallContextFactory);
            } else {
                ((StandaloneCatalog) cur).initialize((StandaloneCatalog) cur);
            }
        }
    }

    //
    // Build the LoaderCallback that is required to build the catalog from the xml from a module that knows
    // nothing about catalog.
    //
    // This is a contract between the TenantCatalogCacheLoader and the DefaultCatalogCache
    private CacheLoaderArgument initializeCacheLoaderArgument(final boolean filterTemplateCatalog) {
        final LoaderCallback loaderCallback = new LoaderCallback() {
            @Override
            public Catalog loadCatalog(final List<String> catalogXMLs, final Long tenantRecordId) throws CatalogApiException {
                return loader.load(catalogXMLs, filterTemplateCatalog, tenantRecordId);
            }
        };
        final Object[] args = new Object[1];
        args[0] = loaderCallback;
        final ObjectType irrelevant = null;
        final InternalTenantContext notUsed = null;
        return new CacheLoaderArgument(irrelevant, args, notUsed);
    }

    @VisibleForTesting
    void setDefaultCatalog() {
        try {
            // Provided in the classpath
            this.defaultCatalog = loader.loadDefaultCatalog("EmptyCatalog.xml");
        } catch (final CatalogApiException e) {
            this.defaultCatalog = new ArrayList<>();
            logger.error("Exception loading EmptyCatalog - should never happen!", e);
        }
    }
}
