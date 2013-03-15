/*******************************************************************************
 * BBC News Reader
 * Released under the BSD License. See README or LICENSE.
 * Copyright (c) 2011, Digital Lizard (Oscar Key, Thomas Boby)
 * All rights reserved.
 ******************************************************************************/
package com.digitallizard.bbcnewsreader;


import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.ComponentName;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.content.SharedPreferences.Editor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.os.Messenger;
import android.os.RemoteException;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TableLayout;
import android.widget.TableRow;
import android.widget.TableRow.LayoutParams;
import android.widget.TextView;

import com.digitallizard.bbcnewsreader.data.DatabaseHandler;



public class ReaderActivity extends Activity {
	
	/* constants */
	static final int ACTIVITY_CHOOSE_CATEGORIES = 1;
	static final int CATEGORY_ROW_LENGTH = 4;
	static final int DIALOG_ERROR = 0;
	static final int NEWS_ITEM_DP_WIDTH = 100; //FIXME item width shouldn't be predefined
	
	public static final String PREFS_FILE_NAME = "com.digitallizard.bbcnewsreader_preferences";
	public static final int DEFAULT_ITEM_LOAD_LIMIT = 4;
	public static final int DEFAULT_CLEAR_OUT_AGE = 4;
	public static final boolean DEFAULT_LOAD_IN_BACKGROUND = true;
	public static final boolean DEFAULT_RTC_WAKEUP = true;
	public static final String DEFAULT_LOAD_INTERVAL = "1_hour";
	public static final boolean DEFAULT_DISPLAY_FULL_ERROR = false;
	public static final String PREFKEY_LOAD_IN_BACKGROUND = "loadInBackground";
	public static final String PREFKEY_RTC_WAKEUP = "rtcWakeup";
	public static final String PREFKEY_LOAD_INTERVAL = "loadInterval";
	
	public static final int ERROR_TYPE_GENERAL = 0;
	public static final int ERROR_TYPE_INTERNET = 1;
	public static final int ERROR_TYPE_FATAL = 2;
	static final byte[] NO_THUMBNAIL_URL_CODE = new byte[]{127};
	
	/* variables */
	ScrollView scroller;

	private Messenger resourceMessenger;
	boolean resourceServiceBound;
	boolean loadInProgress;
	private DatabaseHandler database;
	private LayoutInflater inflater; //used to create objects from the XML
	private SharedPreferences settings; //used to save and load preferences
	Button refreshButton;
	TextView statusText;
	String[] categoryNames;
	ArrayList<TableLayout> physicalCategories;
	ItemLayout[][] physicalItems;
	int categoryRowLength; //the number of items to show per row
	Dialog errorDialog;
	boolean errorWasFatal;
	boolean errorDuringThisLoad;
	boolean firstRun;
	Dialog firstRunDialog;
	Dialog backgroundLoadDialog;
	HashMap<String, Integer> itemIds;
	long lastLoadTime;

	/* service configuration */
	//the handler class to process new messages
	class IncomingHandler extends Handler {
		@Override
		public void handleMessage(Message msg){
			//decide what to do with the message
			switch(msg.what){
			case ResourceService.MSG_CLIENT_REGISTERED:
		        //start a load if we haven't loaded within half an hour
		        //TODO make the load time configurable
				long difference = System.currentTimeMillis() - (lastLoadTime * 1000); //the time since the last load
				if(lastLoadTime == 0 || difference > (60 * 60 * 1000)){
					loadData(); //trigger a load
				}
				break;
			case ResourceService.MSG_ERROR:
				Bundle bundle = msg.getData(); //retrieve the data
				errorOccured(bundle.getInt(ResourceService.KEY_ERROR_TYPE), 
						bundle.getString(ResourceService.KEY_ERROR_MESSAGE), bundle.getString(ResourceService.KEY_ERROR_ERROR));
				break;
			case ResourceService.MSG_CATEGORY_LOADED:
				categoryLoadFinished(msg.getData().getString("category"));
				break;
			case ResourceService.MSG_NOW_LOADING:
				loadBegun();
				break;
			case ResourceService.MSG_FULL_LOAD_COMPLETE:
				fullLoadComplete();
				break;
			case ResourceService.MSG_RSS_LOAD_COMPLETE:
				rssLoadComplete();
				break;
			case ResourceService.MSG_THUMB_LOADED:
				thumbLoadComplete(msg.getData().getInt("id"));
				break;
			case ResourceService.MSG_UPDATE_LOAD_PROGRESS:
				int totalItems = msg.getData().getInt("totalItems");
				int itemsLoaded = msg.getData().getInt("itemsDownloaded");
				updateLoadProgress(totalItems, itemsLoaded);
				break;
			default:
				super.handleMessage(msg); //we don't know what to do, lets hope that the super class knows
			}
		}
	}
	final Messenger messenger = new Messenger(new IncomingHandler()); //this is a target for the service to send messages to
	
	private ServiceConnection resourceServiceConnection = new ServiceConnection() {
	    public void onServiceConnected(ComponentName className, IBinder service) {
	        //this runs when the service connects
	    	resourceServiceBound = true; //flag the service as bound
	    	//save a pointer to the service to a local variable
	        resourceMessenger = new Messenger(service);
	        //try and tell the service that we have connected
	        //this means it will keep talking to us
	        sendMessageToService(ResourceService.MSG_REGISTER_CLIENT, null);
	    }

	    public void onServiceDisconnected(ComponentName className) {
	        //this runs if the service randomly disconnects
	    	//if this happens there are more problems than a missing service
	        resourceMessenger = null; //as the service no longer exists, destroy its pointer
	    }
	};
    
    void errorOccured(int type, String msg, String error){
    	// check if we need to fill in the error messages
    	if(msg == null){
    		msg = "null";
    	}
    	if(error == null){
    		error = "null";
    	}
    	
    	// check if we need to shutdown after displaying the message
    	if(type == ERROR_TYPE_FATAL){
    		errorWasFatal = true;
    	}
    	
    	//show a user friendly message or just the error
    	if(settings.getBoolean("displayFullError", DEFAULT_DISPLAY_FULL_ERROR)){
    		showErrorDialog("Error: "+error);
    	}
    	else{
    		//display a user friendly message
    		if(type == ERROR_TYPE_FATAL){
        		showErrorDialog("Fatal error:\n"+msg+"\nPlease try resetting the app.");
        		Log.e("BBC News Reader", "Fatal error: "+msg);
        		Log.e("BBC News Reader", error);
        	}
        	else if(type == ERROR_TYPE_GENERAL){
        		showErrorDialog("Error:\n"+msg);
        		Log.e("BBC News Reader", "Error: "+msg);
            	Log.e("BBC News Reader", error);
        	}
        	else if(type == ERROR_TYPE_INTERNET){
        		// only allow one internet error per load
        		if(!errorDuringThisLoad){
        			errorDuringThisLoad = true;
        			showErrorDialog("Please check your internet connection.");
        		}
        		Log.e("BBC News Reader", "Error: "+msg);
        		Log.e("BBC News Reader", error);
        	}
    	}
    }
    
    void showErrorDialog(String error){
    	// only show the error dialog if one isn't already visible
    	if(errorDialog == null){
	    	AlertDialog.Builder builder = new AlertDialog.Builder(this);
	    	builder.setMessage(error);
	    	builder.setCancelable(false);
	    	builder.setPositiveButton("Close", new DialogInterface.OnClickListener() {
	           public void onClick(DialogInterface dialog, int id) {
	                closeErrorDialog();
	           }
	    	});
	    	errorDialog = builder.create();
	    	errorDialog.show();
    	}
    }
    
    void closeErrorDialog(){
    	errorDialog = null; //destroy the dialog
    	//see if we need to end the program
    	if(errorWasFatal){
    		//crash out
    		//Log.e("BBC News Reader", "Oops something broke. We'll crash now.");
        	System.exit(1); //closes the app with an error code
    	}
    }
    
    void showFirstRunDialog(){
    	AlertDialog.Builder builder = new AlertDialog.Builder(this);
    	String message = "Choose the categories you are interested in. \n\n" +
    			"The fewer categories enabled the lower data usage and the faster loading will be.";
    	builder.setMessage(message);
    	builder.setCancelable(false);
    	builder.setPositiveButton("Choose", new DialogInterface.OnClickListener() {
           public void onClick(DialogInterface dialog, int id) {
        	   closeFirstRunDialog();
        	   //show the category chooser
        	   showCategoryChooser();
           }
    	});
    	firstRunDialog = builder.create();
    	firstRunDialog.show();
    }
    
    void closeFirstRunDialog(){
    	firstRunDialog = null; //destroy the dialog
    }
    
    void showBackgroundLoadDialog(){
    	AlertDialog.Builder builder = new AlertDialog.Builder(this);
    	String message = "Load news in the background? \n\n" +
    			"This could increase data usage but will reduce load times.\n\n" +
    			"If you wish to use the widget, this should be switched on.";
    	builder.setMessage(message);
    	builder.setCancelable(false);
    	builder.setPositiveButton("Yes", new DialogInterface.OnClickListener() {
    		public void onClick(DialogInterface dialog, int id) {
    			closeBackgroundLoadDialog();
    			firstRun = false; //we have finished the first run
    			//save the selected option
    			Editor editor = settings.edit();
    			editor.putBoolean("loadInBackground", true);
    			editor.commit();
    		}
    	});
    	builder.setNegativeButton("No", new DialogInterface.OnClickListener() {
			public void onClick(DialogInterface dialog, int id) {
				closeBackgroundLoadDialog();
				firstRun = false; //we have finished the first run
				//save the selected option
				Editor editor = settings.edit();
    			editor.putBoolean("loadInBackground", false);
    			editor.commit();
			}
		});
    	backgroundLoadDialog = builder.create();
    	backgroundLoadDialog.show();
    }
    
    void closeBackgroundLoadDialog(){
    	backgroundLoadDialog = null;
    }
    
    void updateLoadProgress(int totalItems, int itemsLoaded){
    	statusText.setText("Preloading "+itemsLoaded+" of "+totalItems+" items");
    }
    
    void setLastLoadTime(long time){
		lastLoadTime = time; //store the time
    	//display the new time to the user
    	//check if the time is set
    	if(!loadInProgress){
	    	if(lastLoadTime == 0){
	    		//say we have never loaded
	    		statusText.setText("Never updated.");
	    	}
	    	else{
	    		//set the text to show date and time
	    		String status = "Updated ";
	    		//find out time since last load in milliseconds
	    		long difference = System.currentTimeMillis() - (time * 1000); //the time since the last load
	    		//if within 1 hour, display minutes
	    		if(difference < (1000 * 60 * 60)){
	    			int minutesAgo = (int)Math.floor((difference / 1000) / 60);
	    			if(minutesAgo == 0)
	    				status += "just now";
	    			else if(minutesAgo == 1)
	    				status += minutesAgo + " minute ago";
	    			else
	    				status += minutesAgo + " minutes ago";
	    		}
	    		else{
	    			//if we are within 24 hours, display hours
	    			if(difference < (1000 * 60 * 60 * 24)){
	        			int hoursAgo = (int)Math.floor(((difference / 1000) / 60) / 60);
	        			if(hoursAgo == 1)
	        				status += hoursAgo + " hour ago";
	        			else
	        				status += hoursAgo + " hours ago";
	        		}
	    			else{
	    				//if we are within 2 days, display yesterday
	    				if(difference < (1000 * 60 * 60 * 48)){
	            			status += "yesterday";
	            		}
	    				else{
	    					//we have not updated recently
	    					status += "ages ago";
	    					//TODO more formal message?
	    				}
	    			}
	    		}
				statusText.setText(status);
	    	}
    	}
    }
    
    void loadBegun(){
    	loadInProgress = true; //flag the data as being loaded
    	//show the loading image on the button
    	refreshButton.setText("stop");
    	//tell the user what is going on
    	statusText.setText("Loading feeds...");
    }
    
    void loadData(){
    	//check we aren't currently loading news
    	if(!loadInProgress){
	    	//TODO display old news as old
	    	//tell the service to load the data
	    	sendMessageToService(ResourceService.MSG_LOAD_DATA);
	    	errorDuringThisLoad = false;
    	}
    }
    
    void stopDataLoad(){
    	//check we are actually loading news
    	if(loadInProgress){
    		errorDuringThisLoad = false;
    		//send a message to the service to stop it loading the data
    		sendMessageToService(ResourceService.MSG_STOP_DATA_LOAD);
    	}
    }
    
    void fullLoadComplete(){
    	//check we are actually loading news
    	if(loadInProgress){
	    	loadInProgress = false;
	    	//display the reloading image on the button
	    	refreshButton.setText("reload");
	    	//report the loaded status
	    	setLastLoadTime(settings.getLong("lastLoadTime", 0)); //set the time as unix time
	    	//tell the database to delete old items
	    	database.clearOld();
    	}
    }
    
    void rssLoadComplete(){
    	//check we are actually loading news
    	if(loadInProgress){
    		//tell the user what is going on
    		statusText.setText("Loading items...");
    	}
    }
    
    void showCategoryChooser(){
		//create an intent to launch the category chooser
    	Intent intent = new Intent(this, CategoryChooserActivity.class);
    	//load the boolean array of currently enabled categories
    	boolean[] categoryBooleans = database.getCategoryBooleans();
    	intent.putExtra("categorybooleans", categoryBooleans);
    	startActivityForResult(intent, ACTIVITY_CHOOSE_CATEGORIES);
    }
    
    void thumbLoadComplete(int id){
    	//loop through categories
    	for(int i = 0; i < physicalItems.length; i++){
    		for(int t = 0; t < physicalItems[i].length; t++){
    			if(physicalItems[i][t].getId() == id){
    				//try and get an image for this item
    				byte[] imageBytes = database.getThumbnail(id);
    				//check if any image data was returned
    				if(Arrays.equals(imageBytes,ReaderActivity.NO_THUMBNAIL_URL_CODE))
    				{
    					//sets the image to the no thumbnail image
    					physicalItems[i][t].setImage(R.drawable.no_thumb);
    				}
    				else if(imageBytes != null){
    					//try to construct an image out of the bytes given by the database
    					Bitmap imageBitmap = BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.length); //load the image into a bitmap
    					physicalItems[i][t].setImage(imageBitmap);
    				}
    				else{
    					//set the image to the no thumbnail loaded image
    					physicalItems[i][t].setImage(R.drawable.no_thumb_grey);
    				}
    			}
    		}
    	}
    }
    
    void doBindService(){
    	//load the resource service
    	bindService(new Intent(this, ResourceService.class), resourceServiceConnection, Context.BIND_AUTO_CREATE);
    	resourceServiceBound = true;
    }
    
    void doUnbindService(){
    	//disconnect the resource service
    	//check if the service is bound, if so, disconnect it
    	if(resourceServiceBound){
    		//politely tell the service that we are disconnected
    		sendMessageToService(ResourceService.MSG_UNREGISTER_CLIENT);
    		//remove local references to the service
    		unbindService(resourceServiceConnection);
    		resourceServiceBound = false;
    	}
    }
    
    void sendMessageToService(int what, Bundle bundle){
    	//check the service is bound before trying to send a message
    	if(resourceServiceBound){
	    	try{
				//create a message according to parameters
				Message msg = Message.obtain(null, what);
				//add the bundle if needed
				if(bundle != null){
					msg.setData(bundle);
				}
				msg.replyTo = messenger; //tell the service to reply to us, if needed
				resourceMessenger.send(msg); //send the message
			}
			catch(RemoteException e){
				//We are probably shutting down, but report it anyway
				//Log.e("ERROR", "Unable to send message to service: " + e.getMessage());
			}
			catch(NullPointerException e){
				//the service was probably killed in the background
				//do nothing
			}
    	}
    }
    
    void sendMessageToService(int what){
    	sendMessageToService(what, null);
    } 
    
    /** Called when the activity is first created. */
    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.main);
        
        loadInProgress = false;
        lastLoadTime = 0;
        
        //set up the inflater to allow us to construct layouts from the raw XML code
        inflater = (LayoutInflater)this.getSystemService(Context.LAYOUT_INFLATER_SERVICE);
        
        //make references to ui items
        refreshButton = (Button) findViewById(R.id.refreshButton);
        statusText = (TextView) findViewById(R.id.statusText);
        
        //load the preferences system
        settings = getSharedPreferences(PREFS_FILE_NAME, MODE_PRIVATE); //load settings in read/write form
        loadSettings(); //load in the settings
        
        //load the database
        database = new DatabaseHandler(this);
        firstRun = false;
        if(!database.isCreated()){
        	database.addCategoriesFromXml();
        	firstRun = true;
        	showFirstRunDialog();
        }
        
        createNewsDisplay();
        
        Eula.show(this); //show the eula
        
        //start the service
        Intent intent = new Intent(this, ResourceService.class);
        this.startService(intent);
        doBindService(); // binds the service to this activity to allow communication
    }
    
    public void onResume(){
    	super.onResume(); //call the super class method
    	//update the last loaded display
    	setLastLoadTime(lastLoadTime);
    	//TODO update display more often?
    }
    
    void loadSettings(){
    	//check the settings file exists
    	if(settings != null){
	    	//load values from the settings
	    	setLastLoadTime(settings.getLong("lastLoadTime", 0)); //sets to zero if not in preferences
    	}
    }
    
    void createNewsDisplay(){
    	LinearLayout content = (LinearLayout)findViewById(R.id.newsScrollerContent); //a reference to the layout where we put the news
    	//clear the content area
    	content.removeAllViewsInLayout();
    	
    	//find the width and work out how many items we can add
    	int rowPixelWidth = ((WindowManager) getSystemService(Context.WINDOW_SERVICE)).getDefaultDisplay().getWidth();
    	int rowWidth =  (int)Math.floor(rowPixelWidth / this.getResources().getDisplayMetrics().density); //formula to convert from pixels to dp
    	categoryRowLength = (int)Math.floor(rowWidth / NEWS_ITEM_DP_WIDTH);
    	
        //create the categories
        categoryNames = database.getEnabledCategories()[1]; //string array with category names in it
        physicalCategories = new ArrayList<TableLayout>(categoryNames.length);
        physicalItems = new ItemLayout[categoryNames.length][CATEGORY_ROW_LENGTH]; //the array to hold the news items
        physicalItems = new ItemLayout[categoryNames.length][categoryRowLength]; //the array to hold the news items
        itemIds = new HashMap<String, Integer>();
        //loop through adding category views
        for(int i = 0; i < categoryNames.length; i++){
        	//create the category
        	TableLayout category = (TableLayout)inflater.inflate(R.layout.list_category_item, null);
        	//change the name
        	TextView name = (TextView)category.findViewById(R.id.textCategoryName);
        	name.setText(categoryNames[i]);
        	//set the column span of the name to fit the width of the table
        	LayoutParams layout = (LayoutParams) name.getLayoutParams();
        	layout.span = categoryRowLength - 1;
        	//retrieve the row for the news items
        	TableRow newsRow = (TableRow)category.findViewById(R.id.rowNewsItem);
        	
        	//add some items to each category display
        	//loop through and add x physical news items
        	for(int t = 0; t < categoryRowLength; t++){
        		//add a new item to the display
        		ItemLayout item = (ItemLayout)inflater.inflate(R.layout.list_news_item, null);
        		physicalItems[i][t] = item; //store the item for future use
        		newsRow.addView(item); //add the item to the display
        	}
        	physicalCategories.add(i, category); //store the category for future use
        	content.addView(category); //add the category to the screen
        	
        	//populate this category with news
        	displayCategoryItems(i);
        }
    }
    
    void displayCategoryItems(int category){
    	//load from the database, if there's anything in it
    	NewsItem[] items = database.getItems(categoryNames[category], categoryRowLength);
    	if(items != null){
    		//change the physical items to match this
    		for(int i = 0; i < categoryRowLength; i++){
    			//check we have not gone out of range of the available news
    			if(i < items.length){
    				physicalItems[category][i].setTitle(items[i].getTitle());
    				physicalItems[category][i].setId(items[i].getId());
    				
    				//try and get an thumbnail for this item
    				byte[] thumbBytes = items[i].getThumbnailBytes();
    				//check if any image data was returned
    				if(Arrays.equals(thumbBytes,ReaderActivity.NO_THUMBNAIL_URL_CODE))
    				{
    					//set the image to the no thumbnail image
    					physicalItems[category][i].setImage(R.drawable.no_thumb);
    				}
    				else if(thumbBytes != null){
    					//try to construct an image out of the bytes given by the database
    					Bitmap imageBitmap = BitmapFactory.decodeByteArray(thumbBytes, 0, thumbBytes.length); //load the image into a bitmap
    					physicalItems[category][i].setImage(imageBitmap);
    				}
    				else{
    					//set the image to the default "X"
    					physicalItems[category][i].setImage(R.drawable.no_thumb_grey);
    				}
    			}
    		}
    	}
    }
    
    void categoryLoadFinished(String category){
    	//the database has finished loading a category, we can update
    	//FIXME very inefficient way to turn (string) name into (int) id
    	int id = 0; //the id of the client
    	for(int i = 0; i < categoryNames.length; i++){
    		//check if the name we have been given matches this category
    		if(category.equals(categoryNames[i]))
    			id = i;
    	}
    	displayCategoryItems(id); //redisplay this category
    }
    
    public boolean onCreateOptionsMenu(Menu menu){
    	super.onCreateOptionsMenu(menu);
    	//inflate the menu XML file
    	MenuInflater menuInflater = new MenuInflater(this);
    	menuInflater.inflate(R.layout.options_menu, menu);
    	return true; //we have made the menu so we can return true
    }
    
    protected void onDestroy(){
    	//disconnect the service
    	doUnbindService();
    	super.onDestroy(); //pass the destroy command to the super
    }
    
    public boolean onOptionsItemSelected(MenuItem item){
    	if(item.getTitle().equals("Choose Categories")){
    		//launch the category chooser activity
    		showCategoryChooser();
    	}
    	if(item.getTitle().equals("Settings")){
    		//show the settings menu
    		Intent intent = new Intent(this, SettingsActivity.class);
    		startActivity(intent);
    	}
    	return true; //we have received the press so we can report true
    }
    
    public void onActivityResult(int requestCode, int resultCode, Intent data){
    	//wait for activities to send us result data
    	switch(requestCode){
    	case ACTIVITY_CHOOSE_CATEGORIES:
    		//check the request was a success
    		if(resultCode == RESULT_OK){
    			database.setEnabledCategories(data.getBooleanArrayExtra("categorybooleans"));
    			//reload the ui
    			createNewsDisplay();
    			//check for a first run
    			if(firstRun){
    				loadData(); //make sure selected categories are loaded
    				showBackgroundLoadDialog();
    			}
    		}
    		break;
    	}
    }
    
    public void refreshClicked(View item){
    	//Log.v("view", "width is: "+physicalCategories[1]].getWidth());
    	//start the load if we are not loading
    	if(!loadInProgress)
    		loadData();
    	else
    		stopDataLoad();
    }
    
    public void itemClicked(View view){
    	//retrieve the title of this activity
    	ItemLayout item = (ItemLayout)view; //cast the view to a an itemlayout

    	//check there is an item at this view
    	if(item.isItem()){
    		//launch article view activity
    		Intent intent = new Intent(this, ArticleActivity.class);
	    	intent.putExtra("id", item.getId());
	    	startActivity(intent);
    	}
    }
    
    public void categoryClicked(View view){
    	//FIXME there must be a more elegant way of doing this...
    	//get the parent of this view
    	TableLayout category = (TableLayout)(view.getParent());
    	//find the id of this category by looking it up in the list
    	int id = physicalCategories.indexOf(category);
    	//launch a new activity to show this category
    	Intent intent = new Intent(this, CategoryActivity.class);
    	intent.putExtra("title", categoryNames[id]);
    	startActivity(intent);
    }
}
