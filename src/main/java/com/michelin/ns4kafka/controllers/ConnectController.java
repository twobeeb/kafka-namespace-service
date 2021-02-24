package com.michelin.ns4kafka.controllers;

import com.michelin.ns4kafka.models.AccessControlEntry;
import com.michelin.ns4kafka.models.Connector;
import com.michelin.ns4kafka.models.Namespace;
import com.michelin.ns4kafka.repositories.AccessControlEntryRepository;
import com.michelin.ns4kafka.repositories.ConnectRepository;
import com.michelin.ns4kafka.repositories.NamespaceRepository;
import com.michelin.ns4kafka.repositories.kafka.DelayStartupListener;
import com.michelin.ns4kafka.services.ConnectRestService;
import com.michelin.ns4kafka.validation.ResourceValidationException;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.annotation.Body;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Error;
import io.micronaut.http.annotation.Get;
import io.micronaut.http.annotation.Post;
import io.micronaut.http.hateoas.JsonError;
import io.reactivex.*;
import io.reactivex.annotations.NonNull;
import io.reactivex.functions.Function;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.reactivestreams.Publisher;
import org.reactivestreams.Subscriber;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;
import javax.validation.Valid;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.concurrent.Flow;

@Tag(name = "Connects")
@Controller(value = "/api/namespaces/{namespace}/connects")
public class ConnectController {
    private static final Logger LOG = LoggerFactory.getLogger(ConnectController.class);
    //TODO validate calls and forward to Connect REST API (sync ???)
    @Inject
    ConnectRepository connectRepository;
    @Inject
    NamespaceRepository namespaceRepository;
    @Inject
    AccessControlEntryRepository accessControlEntryRepository;


    @Get("/")
    public Flowable<Connector> list(String namespace){

        return connectRepository.findByNamespace(namespace);
    }

    @Get("/{connector}")
    public Maybe<Connector> getConnector(String namespace, String connector){
        return connectRepository.findByNamespace(namespace)
                .filter(connect -> connect.getMetadata().getName().equals(connect))
                .firstElement();
    }

    @Post
    public Single<ConnectRestService.ConnectInfo> apply(String namespace, @Valid @Body Connector connector){

        LOG.debug("Beginning apply");

        Namespace ns = namespaceRepository.findByName(namespace).get();


        //1. Request is valid enough to perform local validation ?
        // we need :
        // - connector.class
        // - source/sink type (derived from connector.class on remote /connectors-plugins)
        //2. Validate locally

        Flowable<String> rxLocalValidationErrors = connectRepository
                //retrives connectorType from class name
                .getConnectorType(namespace,connector.getSpec().get("connector.class"))
                //pass it to local validator
                .map(connectorType -> ns.getConnectValidator().validate(connector, connectorType))
                .flattenAsFlowable(strings -> strings)
                .onErrorReturn(throwable -> "Failed to find any class that implements Connector and which name matches "+connector.getSpec().get("connector.class"));

        if(!isNamespaceOwnerOfConnect(namespace,connector.getMetadata().getName())) {
            rxLocalValidationErrors = rxLocalValidationErrors.concatWith(
                    Single.just("Invalid value " + connector.getMetadata().getName() +
                            " for name: Namespace not OWNER of this connector"));
        }

        return rxLocalValidationErrors
                .toList()
                .flatMapPublisher(localValidationErrors -> {
                    if(localValidationErrors.isEmpty()){
                        // we have no local validation errors, move on to /validate endpoint on connect
                        return connectRepository.validate(namespace, connector);
                    }else{
                        // we have local validation errors, return just them
                        return Flowable.fromIterable(localValidationErrors);
                    }
                })
                .onErrorResumeNext((Function<? super Throwable, ? extends Publisher<? extends String>>) throwable ->
                        Flowable.just(throwable.getMessage())
                )
                .toList()
                .flatMap(validationErrors -> {
                    if(validationErrors.size()>0){
                        return Single.error(new ResourceValidationException(validationErrors));
                    }else{
                        return connectRepository.createOrUpdate(namespace,connector)
                                .onErrorResumeNext((Function<? super Throwable, ? extends SingleSource<? extends ConnectRestService.ConnectInfo>>) throwable ->
                                        Single.error(new ConnectCreationException(throwable))
                                        );
                    }
                });
    }

    //TODO move elsewhere
    public static class ConnectCreationException extends Exception {
        public ConnectCreationException(Throwable e){
            super(e);
        }
    }

    //TODO move elsewhere
    @Error(global = true)
    public HttpResponse<JsonError> validationExceptionHandler(HttpRequest request, ConnectCreationException e){
        return HttpResponse.badRequest()
                .body(new JsonError(e.getMessage()));
    }


    private boolean isNamespaceOwnerOfConnect(String namespace, String connect) {
        boolean ownershipResult = accessControlEntryRepository.findAllGrantedToNamespace(namespace)
                .stream()
                .filter(accessControlEntry -> accessControlEntry.getSpec().getPermission() == AccessControlEntry.Permission.OWNER)
                .filter(accessControlEntry -> accessControlEntry.getSpec().getResourceType() == AccessControlEntry.ResourceType.CONNECT)
                .anyMatch(accessControlEntry -> {
                    switch (accessControlEntry.getSpec().getResourcePatternType()){
                        case PREFIXED:
                            return connect.startsWith(accessControlEntry.getSpec().getResource());
                        case LITERAL:
                            return connect.equals(accessControlEntry.getSpec().getResource());
                    }
                    return false;
                });
        LOG.debug("Computed ownership for "+namespace+" on "+connect+ ": "+String.valueOf(ownershipResult));
        return ownershipResult;
    }

}
