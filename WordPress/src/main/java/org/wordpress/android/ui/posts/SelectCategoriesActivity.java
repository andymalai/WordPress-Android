package org.wordpress.android.ui.posts;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.support.v7.app.ActionBar;
import android.support.v7.app.AppCompatActivity;
import android.util.LongSparseArray;
import android.util.SparseBooleanArray;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import org.greenrobot.eventbus.Subscribe;
import org.greenrobot.eventbus.ThreadMode;
import org.wordpress.android.R;
import org.wordpress.android.WordPress;
import org.wordpress.android.fluxc.Dispatcher;
import org.wordpress.android.fluxc.generated.TaxonomyActionBuilder;
import org.wordpress.android.fluxc.model.PostModel;
import org.wordpress.android.fluxc.model.SiteModel;
import org.wordpress.android.fluxc.model.TermModel;
import org.wordpress.android.fluxc.store.SiteStore;
import org.wordpress.android.fluxc.store.TaxonomyStore;
import org.wordpress.android.fluxc.store.TaxonomyStore.OnTaxonomyChanged;
import org.wordpress.android.models.CategoryNode;
import org.wordpress.android.util.AppLog;
import org.wordpress.android.util.NetworkUtils;
import org.wordpress.android.util.StringUtils;
import org.wordpress.android.util.ToastUtils;
import org.wordpress.android.util.ToastUtils.Duration;
import org.wordpress.android.util.helpers.ListScrollPositionManager;
import org.wordpress.android.util.helpers.SwipeToRefreshHelper;
import org.wordpress.android.util.helpers.SwipeToRefreshHelper.RefreshListener;
import org.wordpress.android.util.widgets.CustomSwipeRefreshLayout;
import org.xmlpull.v1.XmlPullParserException;
import org.xmlrpc.android.ApiHelper.Method;
import org.xmlrpc.android.XMLRPCClientInterface;
import org.xmlrpc.android.XMLRPCException;
import org.xmlrpc.android.XMLRPCFactory;

import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

import javax.inject.Inject;

public class SelectCategoriesActivity extends AppCompatActivity {
    String finalResult = "";
    private final Handler mHandler = new Handler();
    private ListView mListView;
    private TextView mEmptyView;
    private ListScrollPositionManager mListScrollPositionManager;
    private SwipeToRefreshHelper mSwipeToRefreshHelper;
    private HashSet<Long> mSelectedCategories;
    private ArrayList<CategoryNode> mCategoryLevels;
    private LongSparseArray<Integer> mCategoryRemoteIdsToListPositions = new LongSparseArray<>();
    private SiteModel mSite;

    @Inject SiteStore mSiteStore;
    @Inject TaxonomyStore mTaxonomyStore;
    @Inject Dispatcher mDispatcher;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        ((WordPress) getApplication()).component().inject(this);
        mDispatcher.register(this);

        if (savedInstanceState == null) {
            mSite = (SiteModel) getIntent().getSerializableExtra(WordPress.SITE);
        } else {
            mSite = (SiteModel) savedInstanceState.getSerializable(WordPress.SITE);
        }
        if (mSite == null) {
            ToastUtils.showToast(this, R.string.blog_not_found, ToastUtils.Duration.SHORT);
            finish();
            return;
        }

        setContentView(R.layout.select_categories);
        setTitle(getResources().getString(R.string.select_categories));

        ActionBar actionBar = getSupportActionBar();
        if (actionBar != null) {
            actionBar.setHomeButtonEnabled(true);
            actionBar.setDisplayHomeAsUpEnabled(true);
        }

        mListView = (ListView)findViewById(android.R.id.list);
        mListScrollPositionManager = new ListScrollPositionManager(mListView, false);
        mListView.setChoiceMode(ListView.CHOICE_MODE_MULTIPLE);
        mListView.setItemsCanFocus(false);

        mEmptyView = (TextView) findViewById(R.id.empty_view);
        mListView.setEmptyView(mEmptyView);

        mSelectedCategories = new HashSet<>();

        Bundle extras = getIntent().getExtras();
        if (extras != null) {
            if (extras.containsKey("postModel")) {
                PostModel post = (PostModel) extras.getSerializable("postModel");
                if (post != null) {
                    for (Long categoryId : post.getCategoryIdList()) {
                        mSelectedCategories.add(mTaxonomyStore.getCategoryByRemoteId(mSite, categoryId).getRemoteTermId());
                    }
                }
            }
        }

        // swipe to refresh setup
        mSwipeToRefreshHelper = new SwipeToRefreshHelper(this, (CustomSwipeRefreshLayout) findViewById(R.id.ptr_layout),
                new RefreshListener() {
                    @Override
                    public void onRefreshStarted() {
                        if (!NetworkUtils.checkConnection(getBaseContext())) {
                            mSwipeToRefreshHelper.setRefreshing(false);
                            return;
                        }
                        refreshCategories();
                    }
                });

        populateCategoryList();

        if (NetworkUtils.isNetworkAvailable(this)) {
            mEmptyView.setText(R.string.empty_list_default);
            if (isCategoryListEmpty()) {
                refreshCategories();
            }
        } else {
            mEmptyView.setText(R.string.no_network_title);
        }
    }

    @Override
    protected void onDestroy() {
        mDispatcher.unregister(this);
        super.onDestroy();
    }

    private boolean isCategoryListEmpty() {
        if (mListView.getAdapter() != null) {
            return mListView.getAdapter().isEmpty();
        } else {
            return true;
        }
    }

    private void populateCategoryList() {
        CategoryNode categoryTree = CategoryNode.createCategoryTreeFromList(mTaxonomyStore.getCategoriesForSite(mSite));
        mCategoryLevels = CategoryNode.getSortedListOfCategoriesFromRoot(categoryTree);
        for (int i = 0; i < mCategoryLevels.size(); i++) {
            mCategoryRemoteIdsToListPositions.put(mCategoryLevels.get(i).getCategoryId(), i);
        }

        CategoryArrayAdapter categoryAdapter = new CategoryArrayAdapter(this, R.layout.categories_row, mCategoryLevels);
        mListView.setAdapter(categoryAdapter);
        if (mSelectedCategories != null) {
            for (Long selectedCategory : mSelectedCategories) {
                if (mCategoryRemoteIdsToListPositions.get(selectedCategory) != null) {
                    mListView.setItemChecked(mCategoryRemoteIdsToListPositions.get(selectedCategory), true);
                }
            }
        }
        mListScrollPositionManager.restoreScrollOffset();
    }

    final Runnable mUpdateResults = new Runnable() {
        public void run() {
            mSwipeToRefreshHelper.setRefreshing(false);
            if (finalResult.equals("addCategory_success")) {
                populateCategoryList();
                if (!isFinishing()) {
                    ToastUtils.showToast(SelectCategoriesActivity.this, R.string.adding_cat_success, Duration.SHORT);
                }
            } else if (finalResult.equals("addCategory_failed")) {
                if (!isFinishing()) {
                    ToastUtils.showToast(SelectCategoriesActivity.this, R.string.adding_cat_failed, Duration.LONG);
                }
            }
        }
    };

    public String addCategory(final String category_name, String category_slug, String category_desc, long parent_id) {
        // Return string
        String returnString = "addCategory_failed";

        // Save selected categories
        updateSelectedCategoryList();
        mListScrollPositionManager.saveScrollOffset();

        // Store the parameters for wp.addCategory
        Map<String, Object> struct = new HashMap<String, Object>();
        struct.put("name", category_name);
        struct.put("slug", category_slug);
        struct.put("description", category_desc);
        struct.put("parent_id", parent_id);
        XMLRPCClientInterface client = XMLRPCFactory.instantiate(URI.create(mSite.getXmlRpcUrl()), "", "");
        Object[] params = {
                String.valueOf(mSite.getSiteId()),
                StringUtils.notNullStr(mSite.getUsername()),
                StringUtils.notNullStr(mSite.getPassword()),
                struct};
        Object result = null;
        try {
            result = client.call(Method.NEW_CATEGORY, params);
        } catch (XMLRPCException e) {
            AppLog.e(AppLog.T.POSTS, e);
        } catch (IOException e) {
            AppLog.e(AppLog.T.POSTS, e);
        } catch (XmlPullParserException e) {
            AppLog.e(AppLog.T.POSTS, e);
        }

        if (result != null) {
            // Category successfully created. "result" is the ID of the new category
            // Initialize the category database
            // Convert "result" (= category_id) from type Object to int
            int category_id = Integer.parseInt(result.toString());

            // Fetch canonical name, can't to do this asynchronously because the new category_name is needed for
            // insertCategory
            final String new_category_name = getCanonicalCategoryName(category_id);
            if (new_category_name == null) {
                return returnString;
            }
            final Activity that = this;
            if (!new_category_name.equals(category_name)) {
                mHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        Toast.makeText(that, String.format(String.valueOf(getText(R.string.category_automatically_renamed)),
                                category_name, new_category_name), Toast.LENGTH_LONG).show();
                    }
                });
            }

            // Insert the new category into database
            // TODO
//            WordPress.wpDB.insertCategory(mSite.getId(), category_id, parent_id, new_category_name);
            returnString = "addCategory_success";
            // auto select new category
            // TODO
//            mSelectedCategories.add(new_category_name);
        }

        return returnString;
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (data != null) {
            final Bundle extras = data.getExtras();

            switch (requestCode) {
            case 0: // Add category
                // Does the user want to continue, or did he press "dismiss"?
                if (extras.getString("continue").equals("TRUE")) {
                    // Get name, slug and desc from Intent
                    final String category_name = extras.getString("category_name");
                    final String category_slug = extras.getString("category_slug");
                    final String category_desc = extras.getString("category_desc");
                    final long parent_id = extras.getInt("parent_id");

                    mSwipeToRefreshHelper.setRefreshing(true);
                    Thread th = new Thread() {
                        public void run() {
                            finalResult = addCategory(category_name, category_slug, category_desc, parent_id);
                            mHandler.post(mUpdateResults);
                        }
                    };
                    th.start();
                    break;
                }
            }// end null check
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putSerializable(WordPress.SITE, mSite);
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        super.onCreateOptionsMenu(menu);
        MenuInflater inflater = getMenuInflater();
        inflater.inflate(R.menu.categories, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(final MenuItem item) {
        int itemId = item.getItemId();
        if (itemId == R.id.menu_new_category) {
            if (NetworkUtils.checkConnection(this)) {
                Intent intent = new Intent(SelectCategoriesActivity.this, AddCategoryActivity.class);
                intent.putExtra(WordPress.SITE, mSite);
                startActivityForResult(intent, 0);
            }
            return true;
        } else if (itemId == android.R.id.home) {
            saveAndFinish();
            return true;
        }

        return super.onOptionsItemSelected(item);
    }

    private String getCanonicalCategoryName(int category_id) {
        String new_category_name = null;
        Map<?, ?> result = null;
        XMLRPCClientInterface client = XMLRPCFactory.instantiate(URI.create(mSite.getXmlRpcUrl()), "", "");
        Object[] params = {
                String.valueOf(mSite.getSiteId()),
                StringUtils.notNullStr(mSite.getUsername()),
                StringUtils.notNullStr(mSite.getPassword()),
                "category", category_id
        };
        try {
            result = (Map<?, ?>) client.call(Method.GET_TERM, params);
        } catch (XMLRPCException e) {
            AppLog.e(AppLog.T.POSTS, e);
        } catch (IOException e) {
            AppLog.e(AppLog.T.POSTS, e);
        } catch (XmlPullParserException e) {
            AppLog.e(AppLog.T.POSTS, e);
        }

        if (result != null) {
            if (result.containsKey("name")) {
                new_category_name = result.get("name").toString();
            }
        }
        return new_category_name;
    }

    private void refreshCategories() {
        mSwipeToRefreshHelper.setRefreshing(true);
        mListScrollPositionManager.saveScrollOffset();
        updateSelectedCategoryList();
        mDispatcher.dispatch(TaxonomyActionBuilder.newFetchCategoriesAction(mSite));
    }

    @Override
    public void onBackPressed() {
        saveAndFinish();
        super.onBackPressed();
    }

    private void updateSelectedCategoryList() {
        SparseBooleanArray selectedItems = mListView.getCheckedItemPositions();
        for (int i = 0; i < selectedItems.size(); i++) {
            long categoryRemoteId = mCategoryLevels.get(selectedItems.keyAt(i)).getCategoryId();
            if (selectedItems.get(selectedItems.keyAt(i))) {
                mSelectedCategories.add(categoryRemoteId);
            } else {
                mSelectedCategories.remove(categoryRemoteId);
            }
        }
    }

    private void saveAndFinish() {
        Bundle bundle = new Bundle();
        updateSelectedCategoryList();
        List<TermModel> categories = new ArrayList<>();
        for (Long categoryRemoteId : mSelectedCategories) {
            categories.add(mTaxonomyStore.getCategoryByRemoteId(mSite, categoryRemoteId));
        }
        bundle.putSerializable("selectedCategories", new ArrayList<>(categories));
        Intent mIntent = new Intent();
        mIntent.putExtras(bundle);
        setResult(RESULT_OK, mIntent);
        finish();
    }

    private int getCheckedItemCount(ListView listView) {
        return listView.getCheckedItemCount();
    }

    @SuppressWarnings("unused")
    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onTaxonomyChanged(OnTaxonomyChanged event) {
        switch (event.causeOfChange) {
            case FETCH_CATEGORIES:
                mSwipeToRefreshHelper.setRefreshing(false);

                if (event.isError()) {
                    if (!isFinishing()) {
                        ToastUtils.showToast(SelectCategoriesActivity.this, R.string.category_refresh_error, Duration.LONG);
                    }
                } else {
                    populateCategoryList();
                }
                break;
        }
    }
}
