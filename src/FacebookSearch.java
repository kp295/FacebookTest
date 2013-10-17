import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.SortedSet;
import java.util.TreeSet;

import com.restfb.Connection;
import com.restfb.DefaultFacebookClient;
import com.restfb.FacebookClient;
import com.restfb.FacebookClient.AccessToken;
import com.restfb.Parameter;
import com.restfb.batch.BatchRequest;
import com.restfb.batch.BatchRequest.BatchRequestBuilder;
import com.restfb.batch.BatchResponse;
import com.restfb.types.*;

public class FacebookSearch {

	// Authorization Token - must be updated or program won't have permissions 
	// to perform queries
	private final String MY_AUTH_TOKEN = "CAACEdEose0cBAK81mXptIBM0DcEniUPr259LPspZCwZB7fG9h4W3bw4Ib6vk15fbytKljxg9CQemv8o6aDoCJYThUjjdOyq8RdRQzEjxnJWtOMrypkjzec4cZBZB2HI8a5DmkAiUX3VnvzyZAL2tPcBNkeugZAM1mMF7sZCgHz6iC2EH4DJnMl5AxxC9M4islLsV1L4mcksxQZDZD";
	
	// restFB stuff to perform actual facebook queries
	private FacebookClient facebookClient;
	
	// Number of user's facebook friends 
	private int numFriends;
	
	// Variables for debug outputs
	// NOTE: by default, program outputs percentage toward completion. 
	//       Set verbose = true for more specific print statements
	private boolean verbose = false; 
	private double totOutputs;
	private int outputCount;

	/**
	 * Default constructor
	 */
	public FacebookSearch() {
		facebookClient = new DefaultFacebookClient(MY_AUTH_TOKEN);
	}

	/**
	 * Outputs a list of facebook friends sorted by number of mutual friends 
	 * 
	 * Parameters:
	 *   - maxn : number of ranks to show (not the number of results, as tied 
	 *            results collectively count as one rank)
	 *            NOTE: changing maxn has very little effect on execution time,
	 *                  as the program must still grab data for ALL friends
	 * 
	 * Required Permissions:
	 *   - user_friends
	 */
	private void mostMutualFriends(int maxn) {
		long start = System.currentTimeMillis();
		
		// Get friends data
		Connection<User> myFriends = getFriends();
		numFriends = myFriends.getData().size();
		
		// Initialize data structure (synchronized + sorted) for storing 
		// Friends 
		TreeSet<Friend> treeSet = new TreeSet<Friend>(new Comparator<Friend>(){
			public int compare(Friend a, Friend b) {
				return (b.mutualFriends >= a.mutualFriends) ? 1 : -1;
			}
		}); 
		SortedSet<Friend> topFriends = Collections.synchronizedSortedSet(treeSet);
		
		// Print statements for debugging purposes
		if (!verbose) {
			totOutputs = Math.ceil(numFriends/50.0)*6;
			outputCount = 0;
		}
		System.out.println("Friend Count: " + numFriends);
		
		// Spawn the required number of threads to execute all batch requests 
		// in parallel
		List<BatchExecutor> threads = new ArrayList<BatchExecutor>();
		for (int i=0; i<numFriends; i+=50) {
			BatchExecutor b = new BatchExecutor(myFriends.getData(), i, topFriends);
			b.start();
			threads.add(b);
		}
		
		// Wait for all batch request threads to finish executing 
		for (BatchExecutor b : threads) {
			try {
				b.join();
			} catch (InterruptedException e) {}
		}
		
		// Output sorted list
		outputSortedSet(topFriends, maxn);
		
		long end = System.currentTimeMillis();
		long duration = (end-start)/1000;
		System.out.printf("Duration: %ds\n", duration);
	}
	
	/**
	 * Get facebook friend list
	 * 
	 * Returns: 
	 *   - Connection<User> of friends
	 */
	private Connection<User> getFriends() {
		return facebookClient.fetchConnection("me/friends", User.class);
	}
	
	/**
	 * Builds a batch request for mutual friends from a given list of facebook 
	 * friends 
	 * 
	 * Parameters:
	 *   - users : list of friends
	 *   - start : start index within full friend list [FOR DEBUGGING]
	 *   - end   : end index within full friend list [FOR DEBUGGING]
	 * 
	 * Returns:
	 *   - List of BatchRequests to execute
	 */
	private List<BatchRequest> buildUserBatchRequest(List<User> users, int start, int end) throws Exception {
		// Print statements for debugging purposes
		if (verbose) {
			System.out.printf("Building batch request[%03d-%03d]...\n", start, end);
		} else {
			synchronized(this) {
				System.out.printf("Executing...[%05.2f%%]\n", ++outputCount*100.0/totOutputs);
			}
		}
		
		// Initialize data structure for storing BatchRequests
		List<BatchRequest> batchRequests = new ArrayList<BatchRequest>();
		
		// Check that batch is not too large
		int batchSize = users.size();
		if (batchSize > 50) {
			throw new Exception("Too many requests, maximum batch size is 50.");
		}
		
		// Build BatchRequests and add them to list
		for (int i = 0; i < batchSize; i++) {
			BatchRequest request = new BatchRequestBuilder("me/mutualfriends/"+users.get(i).getId()).build();
			batchRequests.add(request);
		}
		
		// Print statements for debugging purposes
		if (verbose) {
			System.out.printf("Building batch request[%03d-%03d]...DONE\n", start, end);
		} else {
			synchronized(this) {
				System.out.printf("Executing...[%05.2f%%]\n", ++outputCount*100.0/totOutputs);
			}
		}
		
		return batchRequests;
	}
	
	/**
	 * Processes batch responses received from facebook and adds them to 
	 * a given set
	 * 
	 * Parameters:
	 *   - bachResponses : batch responses received from facebook
	 *   - friends       : list of friends
	 *   - friendSet     : set containing friend entries 
	 *   - start         : start index within full friend list [FOR DEBUGGING]
	 *   - end           : end index within full friend list [FOR DEBUGGING]
	 */
	private void processUserBatchResponse(List<BatchResponse> batchResponses, List<User> friends, SortedSet<Friend> friendSet, int start, int end) {
		// Print statements for debugging purposes
		if (verbose) {
			System.out.printf("Processing batch response[%03d-%03d]...\n", start, end);
		} else {
			synchronized(this) {
				System.out.printf("Executing...[%05.2f%%]\n", ++outputCount*100.0/totOutputs);
			}
		}
		
		// Process batch responses
		BatchResponse fResponse;
		int batchSize = batchResponses.size();
		for (int i=0; i<batchSize; i++) {
			fResponse = batchResponses.get(i);
			if (fResponse.getCode() != 200) {
				System.out.println("ERROR: Batch request failed: " + fResponse);
				continue;
			}
			Connection<User> mutualFriends = 
					new Connection<User>(facebookClient, fResponse.getBody(), User.class);
			friendSet.add(new Friend(friends.get(i).getName(), mutualFriends.getData().size()));
		}
		
		// Print statements for debugging purposes
		if (verbose) {
			System.out.printf("Processing batch response[%03d-%03d]...DONE\n", start, end);
		} else {
			synchronized(this) {
				System.out.printf("Executing...[%05.2f%%]\n", ++outputCount*100.0/totOutputs);
			}
		}	
	}
	
	/**
	 * Builds a batch request from a given list of facebook friends 
	 * 
	 * Parameters:
	 *   - friendSet : set containing friend entries sorted by number of 
	 *                 mutual friends  
	 *   - maxn      : number of ranks to show (not the number of results, 
	 *                 as tied results collectively count as one rank)
	 */
	private void outputSortedSet(SortedSet<Friend> friendSet, int maxn) {
		System.out.println("#===========================================================");
		String msg = (maxn <= 0) ? "# Outputting full sorted list..." : ("# Outputting top " + maxn + " results...");
		System.out.println(msg);
		System.out.println("#===========================================================");
		
		// if maxn <= 0, output ALL results
		if (maxn <= 0) maxn = Integer.MAX_VALUE;
		
		// Iterate through set, outputting entries until either displayed 
		// ranks has reached maxn or there are no more entries to display
		int i = 0;
		Iterator<Friend> friendItr = friendSet.iterator();
		int prevMF = -1;
		while(friendItr.hasNext()) {
			Friend f = friendItr.next();
			if (f.mutualFriends != prevMF) {
				i++;
				prevMF = f.mutualFriends;
			}
			
			if (i > maxn) break;
			
			System.out.printf("[%-3d] %-30s MutualFriends: %s\n", i, f.name, f.mutualFriends);
		}
	}
	
	/** 
	 * Inner class that builds and executes batch requests in parallel to 
	 * help speed up pulling data from facebook
	 */
	class BatchExecutor extends Thread {
		List<User> users;
		int startIndex;
		SortedSet<Friend> topFriends;
		List<BatchRequest> batchList;
		
		/**
		 * Default constructor
		 * @param u : list of facebook friends 
		 * @param s : start index within u to build batch from
		 * @param tf : set of friends to store results in
		 */
		public BatchExecutor(List<User> u, int s, SortedSet<Friend> tf) {
			users = u;
			startIndex = s;
			topFriends = tf;
		}
		
		/**
		 * Build and execute batch request, then process batch response
		 */
		public void run() {
			// endIndex is either startIndex+50 or the end of the list
			int endIndex = (numFriends-startIndex > 50) ? startIndex+50 : numFriends-1;
			
			// Build batch request
			try {
				batchList = buildUserBatchRequest(users.subList(startIndex,endIndex), startIndex, endIndex);
			} catch (Exception e) {
				System.out.println("ERROR: " + e.getMessage());
				return;
			}
			
			// Print statements for debugging purposes
			if (verbose) {
				System.out.printf("Executing batch request[%03d-%03d]...\n", startIndex, endIndex);
			} else {
				synchronized(this) {
					System.out.printf("Executing...[%05.2f%%]\n", ++outputCount*100.0/totOutputs);
				}
			}
			
			// Execute batch request
			List<BatchResponse> batchResponses =
					  facebookClient.executeBatch((BatchRequest[]) batchList.toArray(new BatchRequest[0]));
			
			// Print statements for debugging purposes
			if (verbose) {
				System.out.printf("Executing batch request[%03d-%03d]...DONE\n", startIndex, endIndex);
			} else {
				synchronized(this) {
					System.out.printf("Executing...[%05.2f%%]\n", ++outputCount*100.0/totOutputs);
				}
			}
			
			// Process batch responses
			processUserBatchResponse(batchResponses, users.subList(startIndex,endIndex), topFriends, startIndex, endIndex);			
		}
		
	}
	
	/**
	 * Program Entry Point
	 */
	public static void main(String[] args) {
		FacebookSearch fbSearch = new FacebookSearch();
		fbSearch.mostMutualFriends(0);
	}
	
/**
	private void testSingleObjectFetch() {
		System.out.println("#=================================================");
		System.out.println("# Testing single object fetch...");
		System.out.println("#=================================================");
		
		User user = facebookClient.fetchObject("me", User.class);
		Page page = facebookClient.fetchObject("cocacola", Page.class);
		
		System.out.println("User name: " + user.getName());
		System.out.println("Page likes: " + page.getLikes());
	}
	
	private void testMultipleObjectsFetch() {
		System.out.println("#=================================================");
		System.out.println("# Testing multiple objects fetch...");
		System.out.println("#=================================================");
		
		FetchObjectsResults fetchObjectsResults = facebookClient.fetchObjects(Arrays.asList("me", "cocacola"), FetchObjectsResults.class);
		System.out.println("User name: " + fetchObjectsResults.me.getName());
		System.out.println("Page likes: " + fetchObjectsResults.page.getLikes());
	}
	
	private void testConnectionsFetch() {
		System.out.println("#=================================================");
		System.out.println("# Testing connections fetch...");
		System.out.println("#=================================================");
		
		Connection<User> myFriends = facebookClient.fetchConnection("me/friends", User.class);
		Connection<Post> myFeed = facebookClient.fetchConnection("me/feed", Post.class);
	
		System.out.println("Count of my friends: " + myFriends.getData().size());
		System.out.println("First item in my feed: " + myFeed.getData().get(0));
		
		for (List<Post> myFeedConnectionPage : myFeed) {
			for (Post post : myFeedConnectionPage) {
				System.out.println("Post: " + post);
			}
		}
	}
	
	private void testSearch() {
		System.out.println("#=================================================");
		System.out.println("# Testing search...");
		System.out.println("#=================================================");
		
		Connection<Post> publicSearch = facebookClient.fetchConnection("search", Post.class,
				Parameter.with("q", "zynga"), Parameter.with("type", "post"));
		Connection<User> targetedSearch = facebookClient.fetchConnection("me/home", User.class,
				Parameter.with("q", "Snow"), Parameter.with("type", "user"));
	
		//System.out.println("Public search for 'zynga', first ten search results...");
		//for (int i=0; i<10; i++) {
		//	System.out.println("  - " + publicSearch.getData().get(i).getMessage());	
		//}
		System.out.println("Posts on my wall by friends named Snow: " + targetedSearch.getData().size());
		for (List<User> targetedSearchConnectionPage : targetedSearch) {
			for (User user : targetedSearchConnectionPage) {
				System.out.println("  - " + user.getName());	
			}
		}
	}
	
	private void testInsightsFetch() {
		System.out.println("#=================================================");
		System.out.println("# Testing insights fetch...");
		System.out.println("#=================================================");
		
		Connection<Insight> insights = facebookClient.fetchConnection("2439131959/insights", Insight.class);
		for (Insight insight : insights.getData()) {
			System.out.println(insight.getName());
		}
	}
	
	private void search(String searchText, int numResults) {
		Connection<Post> publicSearch = facebookClient.fetchConnection("search", Post.class,
				Parameter.with("q", searchText), Parameter.with("type", "post"));
		System.out.println("Public search for '" + searchText + "', first " + numResults + " search results...");
		for (int i=0; i<numResults; i++) {
			System.out.println("["+(i+1)+"] - " + publicSearch.getData().get(i).getMessage());	
		}
	}*/
	
}