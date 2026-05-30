package examples;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Tender Impulse API client library for Java.
 *
 * Provides functionality to:
 * - Retrieve tenders and contract awards
 * - Authenticate using Bearer tokens
 * - Decrypt API responses
 * - Validate response integrity using CRC/MD5 checksums
 * - Download and store tender documents
 * - Process API data into Java objects
 *
 * Compatible with Java 17 and above.
 */
public class GetTendersExample {
	/**
     * Local folder where tender documents will be stored.
     */
	public static final String STORE_PATH="documents/";
	
	/**
     * Access token provided by Tender Impulse.
     */
	public static final String ACCESS_TOKEN="your_access_token";
	
	/**
     * AES decryption key provided by Tender Impulse.
     */
	public static final String KEY="your_key";
	
	/**
     * Entry point.
     */
	public static void main(String[] args){
		long lastId=6771840;
		JSONObject jsonDetails=getTenders(lastId);
		if("success".equals(jsonDetails.getString("status"))) {
			JSONArray tenders=jsonDetails.getJSONArray("tenders");
			long lastFetchId=Long.parseLong(jsonDetails.getString("last_fetch_id"));
			System.out.println("Tenders Fetched: "+tenders.length());
			System.out.println("Last Fetch Id: "+lastFetchId);
			System.out.println("Tenders: "+tenders.toString());
		}else{
			System.out.println("Error while getting tenders: "+jsonDetails.getString("msg"));
		}
    }
	
	/**
     * Calls Tender Impulse API and retrieves tender records.
     *
     * @param lastId Last tender ID already processed.
     * @return JSON object containing status and tender data.
     */
	public static JSONObject getTenders(long lastId) {
        JSONObject jsonResponse=new JSONObject();
		try{
        	String urlStr="https://tenderimpulse.com/web-api/tender/v2/uat.php?lastid="+lastId;
            URL url=new URL(urlStr);
		    HttpURLConnection httpCon=(HttpURLConnection) url.openConnection();
            httpCon.setDoOutput(true);
            httpCon.setRequestMethod("GET");
            
            // Bearer authentication
            httpCon.setRequestProperty("Authorization", "Bearer " + ACCESS_TOKEN);
            
            httpCon.setRequestProperty("Content-Type", "application/json");
            
    		int responseCode = httpCon.getResponseCode();
            if(responseCode==200){
            	// Read response body
                StringBuffer response = new StringBuffer();

                BufferedReader in = new BufferedReader(new InputStreamReader(httpCon.getInputStream()));
                String inputLine;

                while ((inputLine = in.readLine()) != null) {
                    response.append(inputLine);
                }
                in.close();
                
                JSONObject json=new JSONObject(response.toString());
                
                // Decrypt API payload
                String decrypt=decrypt(json.getString("data"));
                
                // Validate integrity using CRC hash
                String calculatedCrc=md5(decrypt);
                
                if(!calculatedCrc.equalsIgnoreCase(json.getString("crc"))){
                	throw new Exception("Message transmission error");
                }
                
                JSONObject jsonDetails=new JSONObject(decrypt);
                
                if(!"success".equals(jsonDetails.getString("status"))) {
                	throw new Exception(jsonDetails.getString("msg"));
                }
                
                // Process tenders
                JSONArray tenders=jsonDetails.getJSONArray("tenders");
                JSONArray responseTenders=new JSONArray();
                for(int i=0;i<tenders.length();i++){
                	JSONObject tender=tenders.getJSONObject(i);
                	JSONObject responseTender=new JSONObject();
                	
                	// Copy tender metadata
                	responseTender.put("tender_id", tender.getString("tender_id"));
                	responseTender.put("title", tender.getString("title"));
                	responseTender.put("authority_name", tender.getString("authority_name"));
                	responseTender.put("address", tender.getString("address"));
                	responseTender.put("tel", tender.getString("tel"));
                	responseTender.put("fax", tender.getString("fax"));
                	responseTender.put("email", tender.getString("email"));
                	responseTender.put("web", tender.getString("web"));
                	responseTender.put("contact_name", tender.getString("contact_name"));
                	responseTender.put("contract_type", tender.getString("contract_type"));
                	responseTender.put("sectors", tender.getString("sectors"));
                	responseTender.put("cpv_codes", tender.getString("cpv_codes"));
                	responseTender.put("country", tender.getString("country"));
                	responseTender.put("original_source", tender.getString("original_source"));
                	responseTender.put("location", tender.getString("location"));
                	responseTender.put("reference", tender.getString("reference"));
                	responseTender.put("contract_duration", tender.getString("contract_duration"));
                	responseTender.put("value_of_contract", tender.getString("value_of_contract"));
                	responseTender.put("deadline", tender.getString("deadline"));
                	responseTender.put("other_information", tender.getString("other_information"));
                	responseTender.put("filename", STORE_PATH + tender.getString("filename"));
                	
                	// Download tender document
                	String fileUrl="",fileName="";
                	try{
                		fileName=tender.getString("filename");
                		
                		Path target = Paths.get(STORE_PATH + fileName);
                		Files.createDirectories(target.getParent());
                		
                		fileUrl=tender.getString("filepath");
                    	InputStream inputStream = new URL(fileUrl).openStream();
                    	Files.copy(inputStream, Paths.get(STORE_PATH+fileName), StandardCopyOption.REPLACE_EXISTING);
                    	responseTender.put("filepath", STORE_PATH+fileName);
                    }catch(Exception e){
                    	System.out.println("Error: "+tender.getString("tender_id")+" "+fileUrl+" "+e.getMessage());
                    	throw new Exception("Error while storing file "+STORE_PATH+fileName);
                    }
                	responseTenders.put(responseTender);
                }
                jsonResponse.put("tenders", responseTenders);
                jsonResponse.put("last_fetch_id", jsonDetails.getString("fetchid"));
                jsonResponse.put("status", "success");
            }else{
            	throw new Exception("Could not connect to tenderimpulse.com, error code: "+responseCode);
            }
		} catch (Exception e) {
			System.out.println("Error: " + e.getMessage());
			e.printStackTrace();
			jsonResponse.put("status", "error");
			jsonResponse.put("msg", e.getMessage());
		}
		return jsonResponse;
	}
	
	/**
     * Decrypts encrypted payload received from API.
     *
     * Expected format:
     * encryptedData:iv
     *
     * Uses AES/CBC/PKCS5Padding.
     */
	private static String decrypt(String data) throws Exception{
        try {
            String[] parts = data.split(":");

            IvParameterSpec iv = new IvParameterSpec(Base64.getDecoder().decode(parts[1]));
            SecretKeySpec skeySpec = new SecretKeySpec(KEY.getBytes("UTF-8"), "AES");

            Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5PADDING");
            cipher.init(Cipher.DECRYPT_MODE, skeySpec, iv);

            byte[] decodedEncryptedData = Base64.getDecoder().decode(parts[0]);

            byte[] original = cipher.doFinal(decodedEncryptedData);

            return new String(original);
        } catch (Exception ex) {
            throw new Exception("Unable to decrypt ");
        }
    }
	
	/**
     * Generates MD5 checksum used for response validation.
     *
     * @param s String to hash
     * @return MD5 hash in hexadecimal format
     */
	public static String md5(String s) throws Exception {
        final String MD5 = "MD5";
        try {
            // Create MD5 Hash
            MessageDigest digest = MessageDigest.getInstance(MD5);
            digest.update(s.getBytes());
            byte messageDigest[] = digest.digest();

            // Create Hex String
            StringBuilder hexString = new StringBuilder();
            for (byte aMessageDigest : messageDigest) {
                String h = Integer.toHexString(0xFF & aMessageDigest);
                while (h.length() < 2)
                    h = "0" + h;
                hexString.append(h);
            }
            return hexString.toString();

        } catch (NoSuchAlgorithmException e) {
            throw new Exception("Unable to generate md5");
        }
    }
	
}
