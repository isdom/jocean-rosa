/**
 * 
 */
package org.jocean.ssl;

import java.io.FileInputStream;
import java.io.InputStream;

/**
 * @author isdom
 *
 */
public class File2HEX {

	/**
	 * @param args
	 * @throws Exception 
	 */
	public static void main(final String[] args) throws Exception {
		if ( args.length > 0 ) {
			final InputStream is = new FileInputStream(args[0]);
			try {
				int totalCount = 0;
				while (true) {
					final int b = is.read();
					if ( -1 == b) {
						break;
					}
					totalCount++;
					System.out.print(String.format("0x%02x, ", b));
					if ( (totalCount % 8) == 0) {
						System.out.println();
					}
				}
			}
			finally {
				if ( null != is ) {
					is.close();
				}
			}
		}
	}

}
