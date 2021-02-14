package services;

import com.google.api.client.auth.oauth2.Credential;
import com.google.api.client.extensions.java6.auth.oauth2.AuthorizationCodeInstalledApp;
import com.google.api.client.extensions.jetty.auth.oauth2.LocalServerReceiver;
import com.google.api.client.googleapis.auth.oauth2.GoogleAuthorizationCodeFlow;
import com.google.api.client.googleapis.auth.oauth2.GoogleClientSecrets;
import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport;
import com.google.api.client.http.javanet.NetHttpTransport;
import com.google.api.client.json.JsonFactory;
import com.google.api.client.json.jackson2.JacksonFactory;
import com.google.api.client.util.store.FileDataStoreFactory;
import com.google.api.services.gmail.Gmail;
import com.google.api.services.gmail.GmailScopes;
import com.google.api.services.gmail.model.ListMessagesResponse;
import com.google.api.services.gmail.model.Message;
import com.google.api.services.gmail.model.MessagePart;
import com.google.api.services.gmail.model.MessagePartBody;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.activation.DataHandler;
import javax.activation.DataSource;
import javax.activation.FileDataSource;
import javax.mail.MessagingException;
import javax.mail.Multipart;
import javax.mail.Session;
import javax.mail.internet.InternetAddress;
import javax.mail.internet.MimeBodyPart;
import javax.mail.internet.MimeMessage;
import javax.mail.internet.MimeMultipart;
import java.io.*;
import java.security.GeneralSecurityException;
import java.util.*;
import java.util.function.Predicate;

import enums.GmailUsers;

public class GmailService implements MailService{
    private final Logger log;
    private static final String ACCOUNT_USER = "me";
    private final JsonFactory JSON_FACTORY = JacksonFactory.getDefaultInstance();
    private final String TOKENS_DIRECTORY_PATH = "tokens";
    private final GmailUsers user;

    /**
     * If modifying these scopes, delete your previously saved tokens/ folder.
     */
    private static final List<String> SCOPES = Collections.singletonList(GmailScopes.MAIL_GOOGLE_COM);
    private final String credentialsFilePath;
    private final Gmail service;

    private Gmail getService() {
        if (service == null) {
            log.warn("Attempt to use service after error in initializing has been occurred. Please check logs above" +
                    "for more information");
        }
        return service;
    }

    public GmailService(GmailUsers user) {
        log = LogManager.getLogger(String.format("services.GmailService(%s)", user.toString()));
        credentialsFilePath = File.separator + ".." + File.separator + user.getCredentialsFileName();
        this.user = user;
        Gmail service;
        try {
            service = createGmailService();
        } catch (IOException | GeneralSecurityException e) {
            service = null;
            log.warn(String.format("Error in creating instance of services.GmailService for %s user - %s", user.toString(),
                    e.getMessage()));
            log.warn(Arrays.toString(e.getStackTrace()));
        }
        this.service = service;
    }

    /**
     * Creates an authorized Credential object.
     *
     * @param HTTP_TRANSPORT The network HTTP Transport.
     * @return An authorized Credential object.
     * @throws IOException If the credentials.json file cannot be found.
     */
    private Credential getCredentials(final NetHttpTransport HTTP_TRANSPORT) throws IOException {
        // Load client secrets.
        InputStream in = GmailService.class.getResourceAsStream(credentialsFilePath);
        if (in == null) {
            throw new FileNotFoundException("Resource not found: " + credentialsFilePath);
        }
        GoogleClientSecrets clientSecrets = GoogleClientSecrets.load(JSON_FACTORY, new InputStreamReader(in));

        // Build flow and trigger user authorization request.
        GoogleAuthorizationCodeFlow flow = new GoogleAuthorizationCodeFlow.Builder(
                HTTP_TRANSPORT, JSON_FACTORY, clientSecrets, SCOPES)
                .setDataStoreFactory(new FileDataStoreFactory(new java.io.File(TOKENS_DIRECTORY_PATH)))
                .setAccessType("offline")
                .build();
        LocalServerReceiver receiver = new LocalServerReceiver.Builder().setPort(8888).build();
        return new AuthorizationCodeInstalledApp(flow, receiver).authorize("user");
    }

    private Gmail createGmailService() throws IOException, GeneralSecurityException {
        final NetHttpTransport HTTP_TRANSPORT = GoogleNetHttpTransport.newTrustedTransport();
        return new Gmail.Builder(HTTP_TRANSPORT, JSON_FACTORY, getCredentials(HTTP_TRANSPORT))
                .setApplicationName("GmailUtil")
                .build();
    }

    private Gmail.Users.Messages.List getMessagesRequestDefault() throws IOException {
        return getService().users().messages().list(ACCOUNT_USER);
    }

    private Gmail.Users.Messages.List getMessagesRequestWithSubject(String messageSubject) throws IOException {
        return getMessagesRequestDefault().setQ(String.format("subject:%s", messageSubject));
    }

    private Gmail.Users.Messages.Attachments.Get getAttachmentRequest(String userId, String messageId,
                                                                      String attachmentId) throws IOException {
        return getService().users().messages().attachments().get(userId, messageId, attachmentId);
    }

    /**
     * Returns list of messages, founded via request with minimal contents
     *
     * @param request
     * @return List of Messages. Each message have only minimal attributes - id and threadId.
     * @throws IOException
     */
    private List<Message> getMessagesMinimal(Gmail.Users.Messages.List request) throws IOException {
        List<Message> messages = new ArrayList<>();
        ListMessagesResponse response = request.execute();
        List<Message> messagesPortion = response.getMessages();
        while (messagesPortion != null) {
            messages.addAll(messagesPortion);
            String nextPageToken = response.getNextPageToken();
            if (nextPageToken == null) {
                log.info(String.format("Getting messages is finished. %s are found", messages.size()));
                break;
            }
            response = request.setPageToken(nextPageToken).execute();
            messagesPortion = response.getMessages();
        }
        return messages;
    }

    private Message getFirstMessageMinimal(Gmail.Users.Messages.List request) throws IOException {
        List<Message> messagesPortion = request.execute().getMessages();
        return Objects.isNull(messagesPortion) ? null : messagesPortion.get(0);
    }

    private Message getMessageWithFullContent(Message minimalisticMessage) throws IOException {
        return getMessageWithFullContent(minimalisticMessage, ACCOUNT_USER);
    }

    private Message getMessageWithFullContent(Message minimalisticMessage, String user)
            throws IOException {
        return getService().users().messages().get(user, minimalisticMessage.getId()).execute();
    }

    private String getMessageText(Message fullMessage) {
        return new String(Base64.getDecoder()
                .decode(fullMessage.getPayload().getParts().get(0).getBody().getData()));
    }

    private byte[] getAttachmentAsByteArray(MessagePart attachment, String messageId) throws IOException {
        String attachmentId = attachment.getBody().getAttachmentId();
        MessagePartBody attachmentBody = getAttachmentRequest(ACCOUNT_USER,
                messageId, attachmentId).execute();
        return Base64.getUrlDecoder().decode(attachmentBody.getData());
    }

    private MimeBodyPart getTextMimePart(String text) throws MessagingException {
        MimeBodyPart mimePart = new MimeBodyPart();
        mimePart.setContent(text, "text/plain");
        return mimePart;
    }

    private MimeBodyPart getAttachmentMimePart(File file) throws MessagingException {
        MimeBodyPart mimePart = new MimeBodyPart();
        DataSource source = new FileDataSource(file);
        mimePart.setDataHandler(new DataHandler(source));
        mimePart.setFileName(file.getName());
        return mimePart;
    }

    private MimeMessage createMimeMessage(String toEmail, String fromEmail, String subject, String bodyText,
                                          File... attachments) throws MessagingException {
        Properties properties = new Properties();
        Session session = Session.getDefaultInstance(properties, null);
        MimeMessage mimeMessage = new MimeMessage(session);
        mimeMessage.setFrom(new InternetAddress(fromEmail));
        mimeMessage.addRecipient(javax.mail.Message.RecipientType.TO, new InternetAddress(toEmail));
        mimeMessage.setSubject(subject);
        Multipart multipart = new MimeMultipart();
        multipart.addBodyPart(getTextMimePart(bodyText));
        if (attachments != null) {
            for (File attachment : attachments) {
                multipart.addBodyPart(getAttachmentMimePart(attachment));
            }
        }
        mimeMessage.setContent(multipart);
        return mimeMessage;
    }

    /**
     * Returns amount of received messages with specified subject or containing specified subject
     * if @subject is null returns amount of all messages
     *
     * @param subject
     * @return amount OR -1 if an error occurred
     */
    public int getReceivedMailsWithSubjectAmount(String subject) {
        Gmail.Users.Messages.List messagesRequest;
        int messageAmount;
        try {
            if (subject == null) {
                messagesRequest = getMessagesRequestDefault();
            } else {
                messagesRequest = getMessagesRequestWithSubject(subject);
            }
            messageAmount = getMessagesMinimal(messagesRequest).size();
        } catch (IOException e) {
            log.warn(String.format("An error has occurred while getting received message amount." +
                    "\nMessage: %s\nStack trace: %s", e.getMessage(), Arrays.toString(e.getStackTrace())));
            messageAmount = -1;
        }
        return messageAmount;
    }

    public int getReceivedMailsAmount() {
        return getReceivedMailsWithSubjectAmount(null);
    }

    /**
     * Returns text of first message with specified message
     *
     * @param messageSubject
     * @return message's text or null if there is no such message
     */
    public String getMessageText(String messageSubject) {
        String messageText;
        try {
            Gmail.Users.Messages.List request = getMessagesRequestWithSubject(messageSubject);
            Message minimalisticMessage = getFirstMessageMinimal(request);
            if (minimalisticMessage == null) {
                log.warn(String.format("There is no message with '%s' subject", messageSubject));
                return null;
            }
            Message fullMessage = getMessageWithFullContent(minimalisticMessage);
            messageText = getMessageText(fullMessage);
        } catch (IOException e) {
            log.warn(String.format("An error has occurred while getting text of message with subject: %s.\n" +
                    "Message: %s\nStack trace: %s", messageSubject, e.getMessage(), Arrays.toString(e.getStackTrace())));
            messageText = null;
        }
        return messageText;
    }

    public boolean isMessageExist(String messageSubject) {
        boolean isMessageExist = false;
        try {
            Gmail.Users.Messages.List request = getMessagesRequestWithSubject(messageSubject);
            isMessageExist = !getMessagesMinimal(request).isEmpty();
        } catch (IOException e) {
            log.warn(String.format("An error has occurred while checking existance of '%s' email.\n" +
                            "Message: %s\n Stack trace: %s", messageSubject, e.getMessage(),
                    Arrays.toString(e.getStackTrace())));
        }
        return isMessageExist;
    }

    public boolean isMessageExist(String messageSubject, int secondsToWait) {
        boolean isMessageExist = false;
        try {
            int waitStepSeconds;
            while (secondsToWait > 0) {
                if (isMessageExist(messageSubject)) {
                    isMessageExist = true;
                    break;
                }
                waitStepSeconds = Math.min(secondsToWait, 5);
                secondsToWait -= waitStepSeconds;
                Thread.sleep(waitStepSeconds * 1_000);
            }
        } catch (InterruptedException e) {
            log.warn(String.format("An error has occurred while waiting for '%s' message", messageSubject));
        }
        return isMessageExist;
    }

    /**
     *
     * @param messageSubject
     * @param attachmentNameCondition
     * @return File dedicated to @attachmentNameCondition
     *         OR null in case inner error, the absence of a message with the specified subject
     *         or absence of a attachment that satisfies the predicate
     */
    public File getAttachment(String messageSubject, Predicate<String> attachmentNameCondition) {
        File attachment;
        try {
            Gmail.Users.Messages.List request = getMessagesRequestWithSubject(messageSubject);
            Message minimalisticMessage = getFirstMessageMinimal(request);
            if (minimalisticMessage == null) {
                log.warn(String.format("Impossible to get attachment because there is no message with '%s' subject",
                        messageSubject));
                return null;
            }
            Message fullMessage = getMessageWithFullContent(minimalisticMessage);
            List<MessagePart> parts = fullMessage.getPayload().getParts();
            Optional<MessagePart> attachmentPart = parts.stream().filter(
                    part -> !part.getFilename().isEmpty() && attachmentNameCondition.test(part.getFilename())).findFirst();
            if (!attachmentPart.isPresent()) {
                log.info(String.format("There is no attachment, which match specified condition, in '%s' email",
                        messageSubject));
                attachment = null;
            } else {
                String attachmentLocation = ATTACHMENT_FOLDER_PATH + attachmentPart.get().getFilename();
                byte[] attachmentAsByteArray = getAttachmentAsByteArray(attachmentPart.get(), minimalisticMessage.getId());
                try (FileOutputStream writer = new FileOutputStream(attachmentLocation)) {
                    writer.write(attachmentAsByteArray);
                    attachment = new File(attachmentLocation);
                } catch (IOException e) {
                    log.warn(String.format("An error has occurred while writing attachment from '%s' email " +
                                    "to %s file\nMessage: %s\n Stack trace: %s", messageSubject,
                            attachmentLocation, e.getMessage(), Arrays.toString(e.getStackTrace())));
                    attachment = null;
                }
            }
        } catch (IOException e) {
            log.warn(String.format("An error has occurred while getting attachment from '%s' email.\n " +
                            "Message: %s\n Stack trace: %s", messageSubject, e.getMessage(),
                    Arrays.toString(e.getStackTrace())));
            attachment = null;
        }

        return attachment;
    }

    /**
     *
     * @param toEmail
     * @param subject
     * @param bodyText
     * @return Message in case it's successfully created or null in case some error
     */
    public Message createMessage(String toEmail, String subject, String bodyText, File... attachments) {
        Message message = new Message();
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        MimeMessage mimeMessage;
        try {
            mimeMessage = createMimeMessage(toEmail, user.getEmail(), subject, bodyText, attachments);
            mimeMessage.writeTo(buffer);
        } catch (MessagingException | IOException e) {
            log.warn(String.format("An error(%s) has occurred while creating message to %s address.\n" +
                    "Message: %s\nStack trace: %s",e.toString(), toEmail, e.getMessage(), Arrays.toString(e.getStackTrace())));
            return null;
        }
        byte[] bytes = buffer.toByteArray();
        String encodedEmail = Base64.getUrlEncoder().encodeToString(bytes);
        message.setRaw(encodedEmail);
        return message;
    }

    /**
     *
     * @param message
     * @return true if message was sent successfully. Otherwise returns false
     */
    public boolean sendMessage(Message message) {
        boolean isSent = false;
        try {
            getService().users().messages().send(ACCOUNT_USER, message).execute();
            isSent = true;
        } catch (IOException e) {
            log.warn(String.format("An error has occurred while sending message by %s user.\nMessage: %s\n" +
                    "Stack trace: %s", user.toString(), e.getMessage(), Arrays.toString(e.getStackTrace())));
        }
        return isSent;
    }

    public boolean deleteAllMessages() {
        boolean isMessagesDeleted = false;
        try {
            Gmail.Users.Messages.List allMessagesRequest = getMessagesRequestDefault();
            List<Message> allMessages = getMessagesMinimal(allMessagesRequest);
            for (Message message : allMessages) {
                service.users().messages().delete(ACCOUNT_USER, message.getId()).execute();
            }
            log.info(String.format("Successfully deleted %s messages for %s user", allMessages.size(), user.toString()));
            isMessagesDeleted = true;
        } catch (IOException e) {
            log.warn(String.format("An error has occurred while deleting all messages for %s user.\n" +
                    "Message: %s\nStack trace: %s", user.toString(), e.getMessage(), Arrays.toString(e.getStackTrace())));
        }
        return isMessagesDeleted;
    }
}
