package services;

import com.google.api.services.gmail.model.Message;

import java.io.File;
import java.util.function.Predicate;

public interface MailService {
    String ATTACHMENT_FOLDER_PATH = System.getProperty("user.dir") + File.separator + "emailAttachments"
            + File.separator;

    boolean sendMessage(Message message);

    Message createMessage(String toEmail, String subject, String bodyText, File... attachments);

    File getAttachment(String messageSubject, Predicate<String> attachmentNameCondition);

    boolean isMessageExist(String messageSubject, int secondsToWait);

    boolean isMessageExist(String messageSubject);

    String getMessageText(String messageSubject);

    int getReceivedMailsAmount();

    int getReceivedMailsWithSubjectAmount(String subject);
}
