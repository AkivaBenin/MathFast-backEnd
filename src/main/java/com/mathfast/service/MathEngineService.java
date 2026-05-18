package com.mathfast.service;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.Random;
import java.util.UUID;

@Service
public class MathEngineService {

    private final StringRedisTemplate redisTemplate;
    private final Random random = new Random();

    public MathEngineService(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    public QuestionDTO generateQuestion(UUID raceId, String playerName, String difficulty) {
        String questionText;
        int answer;
        String[] names = new String[]{"דני", "יוסי", "רחל", "מיכל", "אבי", "תמר"};
        String name1 = names[random.nextInt(names.length)];
        String name2 = names[random.nextInt(names.length)];
        while(name1.equals(name2)) {
            name2 = names[random.nextInt(names.length)];
        }
        
        switch (difficulty.toUpperCase()) {
            case "DIRT":
            case "EASY":
                // Range 1-20 subtraction matching exact template
                int num1 = random.nextInt(15) + 6; // 6 to 20
                int num2 = random.nextInt(num1 - 1) + 1; // 1 to num1 - 1
                questionText = String.format("%s היה לו %d תפוחים. %s לקח לו %d. כמה נשאר לו?", name1, num1, name2, num2);
                answer = num1 - num2;
                break;

            case "REGULAR":
            case "MEDIUM":
                int medType = random.nextInt(3);
                if (medType == 0) {
                    // Multiplication tables up to 12
                    int c = random.nextInt(11) + 2; // 2 to 12
                    int d = random.nextInt(11) + 2; // 2 to 12
                    questionText = String.format("ל-%s יש %d קופסאות. בכל קופסה יש %d כדורים. כמה כדורים יש בסך הכל?", name1, c, d);
                    answer = c * d;
                } else if (medType == 1) {
                    // Triple-digit sequential operations (A + B - C)
                    int a = random.nextInt(40) + 10;
                    int b = random.nextInt(40) + 10;
                    int c = random.nextInt(a + b - 5) + 1;
                    questionText = String.format("חשב את התוצאה: %d + %d - %d", a, b, c);
                    answer = a + b - c;
                } else {
                    // Complete-the-pattern linear logic sequence skipping by 2, 5, or 10
                    int[] intervals = new int[]{2, 5, 10};
                    int step = intervals[random.nextInt(intervals.length)];
                    int start = random.nextInt(15) + 2;
                    questionText = String.format("השלם את הסדרה: %d, %d, %d, _", start, start + step, start + step * 2);
                    answer = start + step * 3;
                }
                break;

            case "HIGHWAY":
            case "HARD":
            default:
                int hardType = random.nextInt(3);
                if (hardType == 0) {
                    // Order of operations (PEMDAS) validation
                    int p1 = random.nextInt(9) + 2; // 2 to 10
                    int p2 = random.nextInt(9) + 2; // 2 to 10
                    int sub1 = random.nextInt(20) + 10;
                    int sub2 = random.nextInt(sub1 - 1) + 1;
                    questionText = String.format("חשב לפי סדר פעולות: (%d * %d) + (%d - %d)", p1, p2, sub1, sub2);
                    answer = (p1 * p2) + (sub1 - sub2);
                } else if (hardType == 1) {
                    // Algebraic linear variable isolation
                    int base = random.nextInt(40) + 10;
                    int total = base + random.nextInt(50) + 5;
                    questionText = String.format("אם x + %d = %d, כמה שווה x?", base, total);
                    answer = total - base;
                } else {
                    // Clean division without fractional remainders
                    int divisor = random.nextInt(11) + 2; // 2 to 12
                    int quotient = random.nextInt(11) + 2; // 2 to 12
                    int dividend = divisor * quotient;
                    questionText = String.format("אם %d / x = %d, כמה שווה x?", dividend, quotient);
                    answer = divisor;
                }
                break;
        }

        long expiresAt = System.currentTimeMillis() + 30000L; // 30 seconds
        String prefix = "room:" + raceId + ":player:" + playerName + ":";
        String nonce = UUID.randomUUID().toString();
        
        redisTemplate.opsForValue().set(prefix + "nonce", nonce, 35, java.util.concurrent.TimeUnit.SECONDS);
        redisTemplate.opsForValue().set(prefix + "answer", String.valueOf(answer), 35, java.util.concurrent.TimeUnit.SECONDS);
        redisTemplate.opsForValue().set(prefix + "q_time", String.valueOf(System.currentTimeMillis()), 35, java.util.concurrent.TimeUnit.SECONDS);
        redisTemplate.opsForValue().set(prefix + "expires_at", String.valueOf(expiresAt), 35, java.util.concurrent.TimeUnit.SECONDS);

        return new QuestionDTO(questionText, nonce, difficulty, answer, expiresAt);
    }

    public static class QuestionDTO {
        public String text;
        public String nonce;
        public String difficulty;
        public int answer;
        public long expiresAt;

        public QuestionDTO(String text, String nonce, String difficulty, int answer, long expiresAt) {
            this.text = text;
            this.nonce = nonce;
            this.difficulty = difficulty;
            this.answer = answer;
            this.expiresAt = expiresAt;
        }
    }
}
