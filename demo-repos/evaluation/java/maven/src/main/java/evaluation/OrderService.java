package evaluation;

@Deprecated
public class OrderService {
    public String submit(String order) {
        return repositorySave(order);
    }

    private String repositorySave(String order) {
        return order;
    }
}
