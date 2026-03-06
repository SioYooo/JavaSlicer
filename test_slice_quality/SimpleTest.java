public class SimpleTest {
    public int add(int a, int b) {
        int sum = a + b;
        int doubled = sum * 2;
        return doubled;
    }

    public String greet(String name) {
        String greeting = "Hello, " + name;
        int length = greeting.length();
        System.out.println(greeting);
        return greeting;
    }

    public int factorial(int n) {
        int result = 1;
        for (int i = 1; i <= n; i++) {
            result = result * i;
        }
        return result;
    }
}
