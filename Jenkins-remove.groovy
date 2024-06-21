pipeline {
    agent any

    parameters {
        string(name: 'MILU2_INFRA_GIT_BRANCH', defaultValue: 'main', description: 'Git branch storing Terraform.')
    }

    environment {
        AWS_ACCESS_KEY_ID     = credentials('aws-secret-key-id')
        AWS_SECRET_ACCESS_KEY = credentials('aws-secret-access-key')
        GIT_CREDENTIAL = 'github-access'
        INSTALLER_DIR = 'terraform'
        TF_VAR_SSH_PRIVATE_KEY = credentials('SSH_PRIVATE_KEY')
    }

    stages {
        stage('Checkout SCM') {
            steps {
                deleteDir()
                dir(INSTALLER_DIR) {
                    checkout([
                        $class: 'GitSCM',
                        branches: [[name: MILU2_INFRA_GIT_BRANCH]],
                        doGenerateSubmoduleConfigurations: false,
                        extensions: [[$class: 'CleanBeforeCheckout']],
                        submoduleCfg: [],
                        userRemoteConfigs: [
                            [
                                credentialsId: GIT_CREDENTIAL,
                                url: 'git@github.com:phanthaihuan/terraform2024.git'
                            ]
                        ]
                    ])
                }
            }
        }

        stage('Init Provider') {
            steps {
                dir(INSTALLER_DIR) {
                    sh 'pwd'
                    sh 'ls -lrth'
                    sh 'terraform init -no-color'
                }
            }
        }

        stage('Validate configuration files') {
            steps {
                dir(INSTALLER_DIR) {
                    sh 'terraform validate -no-color'
                    sh 'if [ $? -ne 0 ]; then exit 1; fi'
                }
            }
        }

        stage('Plan to destroy Resources') {
            steps {
                dir(INSTALLER_DIR) {
                    sh 'terraform plan -destroy -no-color'
                }
            }
        }

        stage('Apply plan to destroy Resources') {
            steps {
                script {
                    dir(INSTALLER_DIR) {
                        def userInput = input(
                            id: 'userInput',
                            message: 'Do you want to proceed for production deployment?',
                            parameters: [
                                string(
                                    name: 'INPUT_VALUE',
                                    defaultValue: '',
                                    description: 'Please enter YES or NO'
                                )
                            ]
                        )

                        if (userInput.trim() == 'YES') {
                            sh """
                                terraform destroy -auto-approve -no-color \
                                -var 'SSH_PRIVATE_KEY=${TF_VAR_SSH_PRIVATE_KEY}'
                            """
                        } else {
                            currentBuild.result = 'ABORTED'
                            return
                        }
                    }
                }
            }
        }
    }
}
