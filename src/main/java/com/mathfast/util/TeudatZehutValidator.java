package com.mathfast.util;

public class TeudatZehutValidator {

    /**
     * Validates an Israeli ID number (Teudat Zehut).
     * The algorithm multiplies each digit by alternating weights of 1 and 2,
     * sums the digits of the products, and checks if the total sum is a multiple of 10.
     *
     * @param tz the ID string to validate
     * @return true if valid, false otherwise
     */
    public static boolean isValidTeudatZehut(String tz) {
        if (tz == null || tz.length() > 9 || !tz.matches("\\d+")) {
            return false;
        }

        // Pad with leading zeros if length is less than 9
        tz = String.format("%09d", Long.parseLong(tz));

        int sum = 0;
        for (int i = 0; i < 9; i++) {
            int digit = Character.getNumericValue(tz.charAt(i));
            int weight = (i % 2 == 0) ? 1 : 2;
            int product = digit * weight;
            
            // If product > 9, add its digits
            if (product > 9) {
                product = (product / 10) + (product % 10);
            }
            sum += product;
        }
        
        return sum % 10 == 0;
    }
}
