package client;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLConnection;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Base64;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Tender Impulse tender API client for Java.
 *
 * Provides functionality to:
 * - Retrieve global tender notices
 * - Authenticate using Bearer tokens
 * - Decrypt API responses
 * - Validate response integrity using CRC/MD5 checksums
 * - Download and store tender documents
 *
 * Compatible with Java 17 and above.
 */
public class TenderImpulseClient {

	/**
	 * Endpoint that serves tender notices.
	 */
	private static final String API_URL = "https://tenderimpulse.com/web-api/tender/v2/uat.php";

	/**
	 * Time allowed for the API call, in milliseconds.
	 */
	private static final int API_TIMEOUT = 90000;

	/**
	 * Time allowed for a single document download, in milliseconds.
	 */
	private static final int DOWNLOAD_TIMEOUT = 120000;

	/**
	 * Local folder where tender documents will be stored.
	 */
	private final Path storePath;

	/**
	 * Access token provided by Tender Impulse.
	 */
	private final String accessToken;

	/**
	 * AES decryption key provided by Tender Impulse.
	 */
	private final String key;

	public TenderImpulseClient(String storePath, String accessToken, String key) {
		this.storePath = Paths.get(storePath).toAbsolutePath();
		this.accessToken = accessToken;
		this.key = key;
	}

	/**
	 * Calls the Tender Impulse API and retrieves the batch of tenders that
	 * follows the given fetch id.
	 *
	 * @param lastId Fetch id of the last tender already processed.
	 * @return On success: {@code {status, tenders, last_fetch_id}}.
	 *         On failure: {@code {status, msg}}.
	 */
	public JSONObject getTenders(long lastId) {

		JSONObject jsonResponse = new JSONObject();

		try {
			JSONObject apiResponse = call(lastId);

			// Decrypt API payload
			String decrypted = decrypt(apiResponse.getString("data"));

			// Validate integrity using CRC hash
			String calculatedCrc = md5(decrypted);

			if (!calculatedCrc.equalsIgnoreCase(apiResponse.getString("crc"))) {
				throw new Exception("Message transmission error");
			}

			JSONObject details = new JSONObject(decrypted);

			if (!"success".equals(details.optString("status"))) {
				throw new Exception(details.optString("msg"));
			}

			// Process tenders
			JSONArray tenders = details.getJSONArray("tenders");
			JSONArray responseTenders = new JSONArray();

			for (int i = 0; i < tenders.length(); i++) {

				JSONObject tender = tenders.getJSONObject(i);
				JSONObject responseTender = new JSONObject();

				// Download the tender document before the record is handed back,
				// so a caller that receives the record always has the file.
				String fileName = tender.optString("filename");
				Path localPath = downloadFile(tender.optString("filepath"), fileName);

				// Copy tender metadata
				responseTender.put("tender_id", tender.optString("tender_id"));
				responseTender.put("title", tender.optString("title"));
				responseTender.put("authority_name", tender.optString("authority_name"));
				responseTender.put("address", tender.optString("address"));
				responseTender.put("tel", tender.optString("tel"));
				responseTender.put("fax", tender.optString("fax"));
				responseTender.put("email", tender.optString("email"));
				responseTender.put("web", tender.optString("web"));
				responseTender.put("contact_name", tender.optString("contact_name"));
				responseTender.put("contract_type", tender.optString("contract_type"));
				responseTender.put("sectors", tender.optString("sectors"));
				responseTender.put("cpv_codes", tender.optString("cpv_codes"));
				responseTender.put("country", tender.optString("country"));
				responseTender.put("original_source", tender.optString("original_source"));
				responseTender.put("location", tender.optString("location"));
				responseTender.put("reference", tender.optString("reference"));
				responseTender.put("contract_duration", tender.optString("contract_duration"));
				responseTender.put("value_of_contract", tender.optString("value_of_contract"));
				responseTender.put("deadline", tender.optString("deadline"));
				responseTender.put("other_information", tender.optString("other_information"));
				responseTender.put("filename", localPath.toString());
				responseTender.put("filepath", localPath.toString());

				responseTenders.put(responseTender);
			}

			jsonResponse.put("status", "success");
			jsonResponse.put("tenders", responseTenders);
			jsonResponse.put("last_fetch_id", Long.parseLong(details.get("fetchid").toString()));

		} catch (Exception e) {
			jsonResponse = new JSONObject();
			jsonResponse.put("status", "error");
			jsonResponse.put("msg", e.getMessage());
		}

		return jsonResponse;
	}

	/**
	 * Performs the authenticated GET request and parses the raw envelope.
	 *
	 * @param lastId Fetch id to resume from.
	 * @return The {@code {data, crc}} envelope returned by the API.
	 */
	private JSONObject call(long lastId) throws Exception {

		URL url = new URL(API_URL + "?lastid=" + lastId);

		HttpURLConnection httpCon = (HttpURLConnection) url.openConnection();
		httpCon.setRequestMethod("GET");
		httpCon.setConnectTimeout(API_TIMEOUT);
		httpCon.setReadTimeout(API_TIMEOUT);

		// Bearer authentication
		httpCon.setRequestProperty("Authorization", "Bearer " + accessToken);
		httpCon.setRequestProperty("Content-Type", "application/json");

		int responseCode = httpCon.getResponseCode();

		if (responseCode != 200) {
			// The body carries the reason, e.g. "Invalid Token" on a 401.
			throw new Exception("Could not connect to tenderimpulse.com, error code: " + responseCode
					+ ", response: " + read(httpCon.getErrorStream()));
		}

		return new JSONObject(read(httpCon.getInputStream()));
	}

	/**
	 * Reads a response stream as UTF-8. A null stream yields an empty string,
	 * since {@code getErrorStream} returns null when there is no error body.
	 */
	private String read(InputStream stream) throws Exception {

		if (stream == null) {
			return "";
		}

		StringBuilder response = new StringBuilder();

		try (BufferedReader in = new BufferedReader(
				new InputStreamReader(stream, StandardCharsets.UTF_8))) {

			String inputLine;

			while ((inputLine = in.readLine()) != null) {
				response.append(inputLine);
			}
		}

		return response.toString();
	}

	/**
	 * Downloads a document into the configured store path, creating any
	 * missing directories along the way.
	 *
	 * @param fileUrl  Remote URL of the document.
	 * @param fileName Path of the document relative to the store path.
	 * @return The local path the document was written to.
	 */
	private Path downloadFile(String fileUrl, String fileName) throws Exception {

		Path localPath = storePath.resolve(fileName);

		try {
			Files.createDirectories(localPath.getParent());

			URLConnection connection = new URL(fileUrl).openConnection();
			connection.setConnectTimeout(DOWNLOAD_TIMEOUT);
			connection.setReadTimeout(DOWNLOAD_TIMEOUT);

			try (InputStream inputStream = connection.getInputStream()) {
				Files.copy(inputStream, localPath, StandardCopyOption.REPLACE_EXISTING);
			}

		} catch (Exception e) {
			throw new Exception("Error while storing file " + localPath + ": " + e.getMessage());
		}

		return localPath;
	}

	/**
	 * Decrypts an encrypted payload received from the API.
	 *
	 * Expected format:
	 * encryptedData:iv
	 *
	 * Uses AES/CBC/PKCS5Padding with a 128 bit key.
	 */
	private String decrypt(String data) throws Exception {

		try {
			String[] parts = data.split(":");

			if (parts.length != 2) {
				throw new Exception("Invalid encrypted payload");
			}

			byte[] encryptedData = Base64.getDecoder().decode(parts[0]);
			IvParameterSpec iv = new IvParameterSpec(Base64.getDecoder().decode(parts[1]));

			SecretKeySpec skeySpec = new SecretKeySpec(normalizeKey(), "AES");

			Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5PADDING");
			cipher.init(Cipher.DECRYPT_MODE, skeySpec, iv);

			return new String(cipher.doFinal(encryptedData), StandardCharsets.UTF_8);

		} catch (Exception e) {
			throw new Exception("Unable to decrypt");
		}
	}

	/**
	 * Forces the key to the 16 bytes AES-128 requires: shorter keys are padded
	 * with '0', longer ones are truncated.
	 */
	private byte[] normalizeKey() {

		byte[] keyBytes = key.getBytes(StandardCharsets.UTF_8);

		if (keyBytes.length == 16) {
			return keyBytes;
		}

		byte[] normalized = Arrays.copyOf(keyBytes, 16);

		for (int i = keyBytes.length; i < 16; i++) {
			normalized[i] = '0';
		}

		return normalized;
	}

	/**
	 * Generates the MD5 checksum used for response validation.
	 *
	 * @param s String to hash.
	 * @return MD5 hash in hexadecimal format.
	 */
	private String md5(String s) throws Exception {

		try {
			MessageDigest digest = MessageDigest.getInstance("MD5");
			byte[] messageDigest = digest.digest(s.getBytes(StandardCharsets.UTF_8));

			StringBuilder hexString = new StringBuilder();

			for (byte aMessageDigest : messageDigest) {
				String h = Integer.toHexString(0xFF & aMessageDigest);

				while (h.length() < 2) {
					h = "0" + h;
				}

				hexString.append(h);
			}

			return hexString.toString();

		} catch (Exception e) {
			throw new Exception("Unable to generate md5");
		}
	}
}
