package enums;

import java.io.File;

public enum GmailUsers {
    EXAMPLE("gmailcredentials" + File.separator + "credentialsExample.json", "examplef@gmail.com");

    private final String credentialsFileName;
    private final String email;

    public String getCredentialsFileName() {
        return credentialsFileName;
    }

    public String getEmail() {
        return email;
    }

    GmailUsers(String credentialsFileName, String email) {
        this.credentialsFileName = credentialsFileName;
        this.email = email;
    }
}
